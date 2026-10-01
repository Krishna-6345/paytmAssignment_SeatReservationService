package com.paytm.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.reservation.domain.*;
import com.paytm.reservation.dto.*;
import com.paytm.reservation.exception.*;
import com.paytm.reservation.metrics.ReservationMetrics;
import com.paytm.reservation.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final UserShowQuotaRepository userShowQuotaRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final IdempotencyHelper idempotencyHelper;
    private final ReservationMetrics reservationMetrics;
    private final ObjectMapper objectMapper;
    private final int maxSeatsPerUser;

    public ReservationService(ShowRepository showRepository,
                              SeatRepository seatRepository,
                              ReservationRepository reservationRepository,
                              UserShowQuotaRepository userShowQuotaRepository,
                              IdempotencyRecordRepository idempotencyRecordRepository,
                              IdempotencyHelper idempotencyHelper,
                              ReservationMetrics reservationMetrics,
                              ObjectMapper objectMapper,
                              @Value("${app.reservation.max-seats-per-user:4}") int maxSeatsPerUser) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.userShowQuotaRepository = userShowQuotaRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.idempotencyHelper = idempotencyHelper;
        this.reservationMetrics = reservationMetrics;
        this.objectMapper = objectMapper;
        this.maxSeatsPerUser = maxSeatsPerUser;
    }

    @Transactional
    public ShowDetailResponse createShow(CreateShowRequest request) {
        Show show = new Show();
        show.setName(request.name().trim());
        show.setPricePaise(request.pricePaise());
        Show savedShow = showRepository.save(show);

        List<String> distinctSeats = request.seats().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();

        if (distinctSeats.isEmpty()) {
            throw new InvalidRequestException("Show must contain at least one valid seat");
        }

        List<Seat> seatsToSave = distinctSeats.stream()
                .map(seatNum -> new Seat(savedShow.getId(), seatNum))
                .toList();

        seatRepository.saveAll(seatsToSave);

        log.info("Created show: id={}, name={}, seatsCount={}", savedShow.getId(), savedShow.getName(), distinctSeats.size());
        return getShowDetails(savedShow.getId());
    }

    @Transactional(readOnly = true)
    public ShowDetailResponse getShowDetails(UUID showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResourceNotFoundException("Show not found with id: " + showId));

        List<Seat> seats = seatRepository.findByShowIdOrderBySeatNumberAsc(showId);

        long available = 0;
        long held = 0;
        long confirmed = 0;

        List<SeatDetail> seatDetails = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            seatDetails.add(new SeatDetail(seat.getSeatNumber(), seat.getStatus().getValue()));
            switch (seat.getStatus()) {
                case AVAILABLE -> available++;
                case HELD -> held++;
                case CONFIRMED -> confirmed++;
            }
        }

        long total = seats.size();
        if (available + held + confirmed != total) {
            log.error("CRITICAL RECONCILIATION INVARIANT VIOLATION: showId={}, available={}, held={}, confirmed={}, total={}",
                    showId, available, held, confirmed, total);
            throw new IllegalStateException("Reconciliation invariant broken for show " + showId);
        }

        ShowCounts counts = new ShowCounts(available, held, confirmed, total);
        return new ShowDetailResponse(show.getId(), show.getName(), show.getPricePaise(), counts, seatDetails);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse reserveSeats(UUID showId, String userId, ReserveSeatsRequest request) {
        // 1. Validate seat selection and sort to guarantee deadlock avoidance
        List<String> sortedSeats = request.seats().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();

        if (sortedSeats.isEmpty()) {
            throw new InvalidRequestException("No valid seats requested");
        }

        // 2. Compute canonical request hash for idempotency validation
        String currentHash = idempotencyHelper.computeRequestHash(showId, sortedSeats);

        // 3. Idempotency Check: (user_id, idempotency_key)
        Optional<IdempotencyRecord> existingRecordOpt = idempotencyRecordRepository
                .findByUserIdAndIdempotencyKey(userId, request.idempotencyKey());

        if (existingRecordOpt.isPresent()) {
            IdempotencyRecord record = existingRecordOpt.get();
            if (record.getRequestHash().equals(currentHash)) {
                log.info("Idempotent replay: returning existing reservation for user={}, key={}", userId, request.idempotencyKey());
                reservationMetrics.incrementDeclined("idempotent_replay");
                return idempotencyHelper.deserializeResponse(record.getResponsePayload());
            } else {
                log.warn("Idempotency conflict: key={} reused with different body by user={}", request.idempotencyKey(), userId);
                reservationMetrics.incrementDeclined("idempotent_replay");
                throw new IdempotencyConflictException("Idempotency key has already been used with different request parameters");
            }
        }

        // 4. Verify Show existence
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResourceNotFoundException("Show not found with id: " + showId));

        // 5. Per-User Quota Enforcement under concurrency via row-level locking
        if (sortedSeats.size() > maxSeatsPerUser) {
            reservationMetrics.incrementDeclined("per_user_limit");
            throw new PerUserLimitExceededException("Requested seats count (" + sortedSeats.size() +
                    ") exceeds maximum per-user limit of " + maxSeatsPerUser);
        }

        // Initialize row if absent, then lock the (show_id, user_id) row
        userShowQuotaRepository.initQuotaIfAbsent(showId, userId);
        UserShowQuota quota = userShowQuotaRepository.findByShowIdAndUserIdForUpdate(showId, userId)
                .orElseGet(() -> new UserShowQuota(showId, userId, 0));

        int currentQuota = quota.getActiveSeatCount() == null ? 0 : quota.getActiveSeatCount();
        if (currentQuota + sortedSeats.size() > maxSeatsPerUser) {
            log.warn("Per-user limit reached: user={}, showId={}, current={}, requested={}, max={}",
                    userId, showId, currentQuota, sortedSeats.size(), maxSeatsPerUser);
            reservationMetrics.incrementDeclined("per_user_limit");
            throw new PerUserLimitExceededException("Booking would exceed maximum per-user quota of " +
                    maxSeatsPerUser + " seats for this show");
        }

        // 6. Atomic conditional UPDATE on seats (... WHERE status='available')
        UUID reservationId = UUID.randomUUID();
        int updatedRows = seatRepository.reserveSeatsAtomically(
                showId,
                sortedSeats,
                reservationId,
                SeatStatus.AVAILABLE,
                SeatStatus.CONFIRMED
        );

        if (updatedRows != sortedSeats.size()) {
            log.warn("Atomic seat reservation declined: showId={}, requested={}, successfullyUpdated={}",
                    showId, sortedSeats, updatedRows);
            reservationMetrics.incrementDeclined("seat_taken");
            throw new SeatNotAvailableException("One or more requested seats are already reserved or unavailable");
        }

        // 7. Persist Reservation entity
        long totalAmountPaise = show.getPricePaise() * sortedSeats.size();
        String seatIdentifiersJson;
        try {
            seatIdentifiersJson = objectMapper.writeValueAsString(sortedSeats);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to format seat identifiers", e);
        }

        Reservation reservation = new Reservation(
                reservationId,
                showId,
                userId,
                seatIdentifiersJson,
                sortedSeats.size(),
                totalAmountPaise,
                ReservationStatus.CONFIRMED,
                request.idempotencyKey()
        );
        reservationRepository.save(reservation);

        // 8. Update user quota
        quota.setActiveSeatCount(currentQuota + sortedSeats.size());
        userShowQuotaRepository.save(quota);

        // 9. Persist Idempotency record with exact response payload
        ReservationResponse response = new ReservationResponse(
                reservationId,
                showId,
                userId,
                sortedSeats,
                totalAmountPaise,
                ReservationStatus.CONFIRMED.getValue()
        );
        String responsePayload = idempotencyHelper.serializeResponse(response);
        IdempotencyRecord idempotencyRecord = new IdempotencyRecord(
                userId,
                request.idempotencyKey(),
                currentHash,
                reservationId,
                responsePayload
        );
        idempotencyRecordRepository.save(idempotencyRecord);

        // 10. Record metrics
        reservationMetrics.incrementConfirmed();
        log.info("Reservation confirmed: id={}, showId={}, userId={}, seats={}, amountPaise={}",
                reservationId, showId, userId, sortedSeats, totalAmountPaise);

        return response;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CancelReservationResponse cancelReservation(UUID reservationId, String currentUserId) {
        // 1. Lock reservation row
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with id: " + reservationId));

        // 2. Owner-only check
        if (!reservation.getUserId().equals(currentUserId)) {
            log.warn("Unauthorized cancellation attempt: reservationId={}, owner={}, requester={}",
                    reservationId, reservation.getUserId(), currentUserId);
            throw new ForbiddenException("Only the reservation owner can cancel this reservation");
        }

        // 3. Guard against duplicate cancellation
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return new CancelReservationResponse(reservationId, "cancelled", "Reservation is already cancelled");
        }

        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new InvalidRequestException("Cannot cancel reservation in status: " + reservation.getStatus());
        }

        // 4. Guarded seat release: only release seats belonging to this reservation and currently confirmed
        int releasedSeats = seatRepository.releaseSeatsGuarded(
                reservationId,
                SeatStatus.CONFIRMED,
                SeatStatus.AVAILABLE
        );

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);

        // 5. Decrement user quota
        userShowQuotaRepository.findByShowIdAndUserIdForUpdate(reservation.getShowId(), currentUserId)
                .ifPresent(quota -> {
                    int updated = Math.max(0, quota.getActiveSeatCount() - reservation.getSeatCount());
                    quota.setActiveSeatCount(updated);
                    userShowQuotaRepository.save(quota);
                });

        log.info("Reservation cancelled: id={}, userId={}, releasedSeats={}", reservationId, currentUserId, releasedSeats);
        return new CancelReservationResponse(reservationId, "cancelled",
                "Reservation cancelled successfully; " + releasedSeats + " seat(s) released");
    }
}
