package com.paytm.reservation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.reservation.domain.*;
import com.paytm.reservation.dto.*;
import com.paytm.reservation.exception.*;
import com.paytm.reservation.metrics.ReservationMetrics;
import com.paytm.reservation.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ReservationServiceTest {

    @Mock
    private ShowRepository showRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserShowQuotaRepository userShowQuotaRepository;

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    private ReservationService reservationService;
    private IdempotencyHelper idempotencyHelper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        idempotencyHelper = new IdempotencyHelper(objectMapper);
        ReservationMetrics metrics = new ReservationMetrics(new SimpleMeterRegistry(), seatRepository);
        reservationService = new ReservationService(
                showRepository,
                seatRepository,
                reservationRepository,
                userShowQuotaRepository,
                idempotencyRecordRepository,
                idempotencyHelper,
                metrics,
                objectMapper,
                4 // max 4 seats per user
        );
    }

    @Test
    @DisplayName("Should successfully confirm reservation with atomic update")
    void testReserveSeats_success() {
        UUID showId = UUID.randomUUID();
        String userId = "user-1";
        Show show = new Show(showId, "Test Concert", 20000L);

        when(idempotencyRecordRepository.findByUserIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        when(userShowQuotaRepository.findByShowIdAndUserIdForUpdate(showId, userId))
                .thenReturn(Optional.of(new UserShowQuota(showId, userId, 0)));
        when(seatRepository.reserveSeatsAtomically(eq(showId), anyList(), any(UUID.class), eq(SeatStatus.AVAILABLE), eq(SeatStatus.CONFIRMED)))
                .thenReturn(2);

        ReserveSeatsRequest request = new ReserveSeatsRequest(List.of("A1", "A2"), "key-123");
        ReservationResponse response = reservationService.reserveSeats(showId, userId, request);

        assertNotNull(response);
        assertEquals("confirmed", response.status());
        assertEquals(40000L, response.amountPaise());
        assertEquals(2, response.seats().size());
        verify(reservationRepository, times(1)).save(any(Reservation.class));
        verify(idempotencyRecordRepository, times(1)).save(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("Should decline with 409 seat_taken when atomic update returns fewer rows")
    void testReserveSeats_seatTaken() {
        UUID showId = UUID.randomUUID();
        String userId = "user-2";
        Show show = new Show(showId, "Test Concert", 20000L);

        when(idempotencyRecordRepository.findByUserIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        when(userShowQuotaRepository.findByShowIdAndUserIdForUpdate(showId, userId))
                .thenReturn(Optional.of(new UserShowQuota(showId, userId, 0)));
        // 2 requested, only 1 updated (conflict)
        when(seatRepository.reserveSeatsAtomically(eq(showId), anyList(), any(UUID.class), eq(SeatStatus.AVAILABLE), eq(SeatStatus.CONFIRMED)))
                .thenReturn(1);

        ReserveSeatsRequest request = new ReserveSeatsRequest(List.of("A1", "A2"), "key-456");
        SeatNotAvailableException ex = assertThrows(SeatNotAvailableException.class, () ->
                reservationService.reserveSeats(showId, userId, request));

        assertEquals("seat_taken", ex.getReason());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should decline with 409 per_user_limit when quota would be exceeded")
    void testReserveSeats_quotaExceeded() {
        UUID showId = UUID.randomUUID();
        String userId = "user-3";
        Show show = new Show(showId, "Test Concert", 20000L);

        when(idempotencyRecordRepository.findByUserIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        // User already has 3 seats reserved
        when(userShowQuotaRepository.findByShowIdAndUserIdForUpdate(showId, userId))
                .thenReturn(Optional.of(new UserShowQuota(showId, userId, 3)));

        // Requesting 2 more seats (3 + 2 = 5 > 4)
        ReserveSeatsRequest request = new ReserveSeatsRequest(List.of("A1", "A2"), "key-quota");
        PerUserLimitExceededException ex = assertThrows(PerUserLimitExceededException.class, () ->
                reservationService.reserveSeats(showId, userId, request));

        assertEquals("per_user_limit", ex.getReason());
        verify(seatRepository, never()).reserveSeatsAtomically(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should replay response when idempotency key and body match")
    void testReserveSeats_idempotentReplay() {
        UUID showId = UUID.randomUUID();
        String userId = "user-4";
        UUID existingResId = UUID.randomUUID();

        List<String> seats = List.of("A1");
        String hash = idempotencyHelper.computeRequestHash(showId, seats);
        ReservationResponse originalResponse = new ReservationResponse(existingResId, showId, userId, seats, 20000L, "confirmed");
        String payload = idempotencyHelper.serializeResponse(originalResponse);

        IdempotencyRecord record = new IdempotencyRecord(userId, "key-rep", hash, existingResId, payload);
        when(idempotencyRecordRepository.findByUserIdAndIdempotencyKey(userId, "key-rep")).thenReturn(Optional.of(record));

        ReserveSeatsRequest request = new ReserveSeatsRequest(seats, "key-rep");
        ReservationResponse response = reservationService.reserveSeats(showId, userId, request);

        assertEquals(existingResId, response.reservationId());
        verify(seatRepository, never()).reserveSeatsAtomically(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should throw 409 conflict when idempotency key is reused with different seats")
    void testReserveSeats_idempotencyConflict() {
        UUID showId = UUID.randomUUID();
        String userId = "user-5";
        UUID existingResId = UUID.randomUUID();

        // Stored hash was for ["A1"]
        String originalHash = idempotencyHelper.computeRequestHash(showId, List.of("A1"));
        IdempotencyRecord record = new IdempotencyRecord(userId, "key-reused", originalHash, existingResId, "{}");
        when(idempotencyRecordRepository.findByUserIdAndIdempotencyKey(userId, "key-reused")).thenReturn(Optional.of(record));

        // Reusing key with seat ["B2"]
        ReserveSeatsRequest request = new ReserveSeatsRequest(List.of("B2"), "key-reused");
        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class, () ->
                reservationService.reserveSeats(showId, userId, request));

        assertEquals("idempotent_replay", ex.getReason());
    }

    @Test
    @DisplayName("Owner can cancel reservation; non-owner is forbidden (403)")
    void testCancelReservation_ownershipAndGuardedRelease() {
        UUID resId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        String owner = "owner-alice";
        String intruder = "intruder-mallory";

        Reservation reservation = new Reservation(resId, showId, owner, "[\"A1\"]", 1, 10000L, ReservationStatus.CONFIRMED, "k1");
        when(reservationRepository.findByIdForUpdate(resId)).thenReturn(Optional.of(reservation));

        // Intruder attempts cancellation -> 403 Forbidden
        assertThrows(ForbiddenException.class, () -> reservationService.cancelReservation(resId, intruder));

        // Owner cancels -> succeeds and releases seats guarded
        when(seatRepository.releaseSeatsGuarded(eq(resId), eq(SeatStatus.CONFIRMED), eq(SeatStatus.AVAILABLE))).thenReturn(1);
        when(userShowQuotaRepository.findByShowIdAndUserIdForUpdate(showId, owner))
                .thenReturn(Optional.of(new UserShowQuota(showId, owner, 1)));

        CancelReservationResponse response = reservationService.cancelReservation(resId, owner);
        assertEquals("cancelled", response.status());
        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
        verify(seatRepository, times(1)).releaseSeatsGuarded(resId, SeatStatus.CONFIRMED, SeatStatus.AVAILABLE);
    }
}
