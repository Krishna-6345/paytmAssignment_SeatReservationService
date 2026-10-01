package com.paytm.reservation.repository;

import com.paytm.reservation.domain.UserShowQuota;
import com.paytm.reservation.domain.UserShowQuotaId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserShowQuotaRepository extends JpaRepository<UserShowQuota, UserShowQuotaId> {

    @Modifying
    @Query(value = """
        INSERT INTO user_show_quotas (show_id, user_id, active_seat_count, updated_at)
        VALUES (:showId, :userId, 0, CURRENT_TIMESTAMP)
        ON CONFLICT (show_id, user_id) DO NOTHING
    """, nativeQuery = true)
    void initQuotaIfAbsent(@Param("showId") UUID showId, @Param("userId") String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM UserShowQuota q WHERE q.showId = :showId AND q.userId = :userId")
    Optional<UserShowQuota> findByShowIdAndUserIdForUpdate(@Param("showId") UUID showId, @Param("userId") String userId);
}
