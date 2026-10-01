package com.paytm.reservation.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class UserShowQuotaId implements Serializable {

    private UUID showId;
    private String userId;

    public UserShowQuotaId() {}

    public UserShowQuotaId(UUID showId, String userId) {
        this.showId = showId;
        this.userId = userId;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserShowQuotaId that = (UserShowQuotaId) o;
        return Objects.equals(showId, that.showId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(showId, userId);
    }
}
