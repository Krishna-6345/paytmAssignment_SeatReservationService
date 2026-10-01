package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ReserveSeatsRequest(
    @NotEmpty(message = "Seats list must not be empty")
    List<String> seats,

    @NotBlank(message = "Idempotency key is required")
    @JsonProperty("idempotency_key")
    String idempotencyKey
) {}
