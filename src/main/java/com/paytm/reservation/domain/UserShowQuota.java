package com.paytm.reservation.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_show_quotas")
@IdClass(UserShowQuotaId.class)
public class UserShowQuota {

    @Id
    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Id
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "active_seat_count", nullable = false)
    private Integer activeSeatCount = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UserShowQuota() {}

    public UserShowQuota(UUID showId, String userId, Integer activeSeatCount) {
        this.showId = showId;
        this.userId = userId;
        this.activeSeatCount = activeSeatCount;
        this.updatedAt = Instant.now();
    }

    public UUID getShowId() {
        return showId;
    }

    public void setShowId(UUID showId) {
        this.showId = showId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Integer getActiveSeatCount() {
        return activeSeatCount;
    }

    public void setActiveSeatCount(Integer activeSeatCount) {
        this.activeSeatCount = activeSeatCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
