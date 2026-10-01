package com.paytm.reservation.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations", uniqueConstraints = {
    @UniqueConstraint(name = "uq_reservations_user_idempotency", columnNames = {"user_id", "idempotency_key"})
})
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "seat_identifiers", nullable = false, columnDefinition = "text")
    private String seatIdentifiers;

    @Column(name = "seat_count", nullable = false)
    private Integer seatCount;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Convert(converter = ReservationStatusConverter.class)
    @Column(nullable = false, length = 32)
    private ReservationStatus status = ReservationStatus.CONFIRMED;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Reservation() {}

    public Reservation(UUID showId, String userId, String seatIdentifiers, Integer seatCount,
                       Long amountPaise, ReservationStatus status, String idempotencyKey) {
        this.showId = showId;
        this.userId = userId;
        this.seatIdentifiers = seatIdentifiers;
        this.seatCount = seatCount;
        this.amountPaise = amountPaise;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Reservation(UUID id, UUID showId, String userId, String seatIdentifiers, Integer seatCount,
                       Long amountPaise, ReservationStatus status, String idempotencyKey) {
        this.id = id;
        this.showId = showId;
        this.userId = userId;
        this.seatIdentifiers = seatIdentifiers;
        this.seatCount = seatCount;
        this.amountPaise = amountPaise;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getSeatIdentifiers() {
        return seatIdentifiers;
    }

    public void setSeatIdentifiers(String seatIdentifiers) {
        this.seatIdentifiers = seatIdentifiers;
    }

    public Integer getSeatCount() {
        return seatCount;
    }

    public void setSeatCount(Integer seatCount) {
        this.seatCount = seatCount;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public void setAmountPaise(Long amountPaise) {
        this.amountPaise = amountPaise;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
