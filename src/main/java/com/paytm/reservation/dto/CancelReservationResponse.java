package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record CancelReservationResponse(
    @JsonProperty("reservation_id")
    UUID reservationId,
    String status,
    String message
) {}
