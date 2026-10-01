package com.paytm.reservation.domain;

import java.io.Serializable;
import java.util.Objects;

public class IdempotencyRecordId implements Serializable {

    private String userId;
    private String idempotencyKey;

    public IdempotencyRecordId() {}

    public IdempotencyRecordId(String userId, String idempotencyKey) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdempotencyRecordId that = (IdempotencyRecordId) o;
        return Objects.equals(userId, that.userId) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, idempotencyKey);
    }
}
