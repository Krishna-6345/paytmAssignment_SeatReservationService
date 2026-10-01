package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String error,
    String message,
    String reason,
    int status,
    @JsonProperty("request_id")
    String requestId,
    Instant timestamp
) {}
