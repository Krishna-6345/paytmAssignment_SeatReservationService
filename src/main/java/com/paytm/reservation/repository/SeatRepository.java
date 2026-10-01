package com.paytm.reservation.repository;

import com.paytm.reservation.domain.Seat;
import com.paytm.reservation.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByShowIdOrderBySeatNumberAsc(UUID showId);

    @Query("SELECT s FROM Seat s WHERE s.showId = :showId AND s.seatNumber IN :seatNumbers ORDER BY s.seatNumber ASC")
    List<Seat> findByShowIdAndSeatNumberInOrderAsc(@Param("showId") UUID showId,
                                                  @Param("seatNumbers") Collection<String> seatNumbers);

    /**
     * Single atomic conditional UPDATE: updates only if status is 'AVAILABLE'.
     * Returns the exact number of rows updated. If < requested seats, double-selling is averted.
     */
    @Modifying
    @Query("""
        UPDATE Seat s
        SET s.status = :confirmedStatus,
            s.reservationId = :reservationId,
            s.updatedAt = CURRENT_TIMESTAMP,
            s.version = s.version + 1
        WHERE s.showId = :showId
          AND s.seatNumber IN :seatNumbers
          AND s.status = :availableStatus
    """)
    int reserveSeatsAtomically(
            @Param("showId") UUID showId,
            @Param("seatNumbers") Collection<String> seatNumbers,
            @Param("reservationId") UUID reservationId,
            @Param("availableStatus") SeatStatus availableStatus,
            @Param("confirmedStatus") SeatStatus confirmedStatus
    );

    /**
     * Guarded cancellation update: updates seats back to 'available' only if currently 'confirmed'
     * for the specified reservationId. Never resurrects seats confirmed to another reservation.
     */
    @Modifying
    @Query("""
        UPDATE Seat s
        SET s.status = :availableStatus,
            s.reservationId = NULL,
            s.updatedAt = CURRENT_TIMESTAMP,
            s.version = s.version + 1
        WHERE s.reservationId = :reservationId
          AND s.status = :confirmedStatus
    """)
    int releaseSeatsGuarded(
            @Param("reservationId") UUID reservationId,
            @Param("confirmedStatus") SeatStatus confirmedStatus,
            @Param("availableStatus") SeatStatus availableStatus
    );

    long countByShowIdAndStatus(UUID showId, SeatStatus status);

    long countByStatus(SeatStatus status);
}
