package com.paytm.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.reservation.dto.ReservationResponse;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class IdempotencyHelper {

    private final ObjectMapper objectMapper;

    public IdempotencyHelper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String computeRequestHash(UUID showId, List<String> sortedSeats) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonicalInput = showId.toString() + ":" + String.join(",", sortedSeats);
            byte[] hash = digest.digest(canonicalInput.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public String serializeResponse(ReservationResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reservation response", e);
        }
    }

    public ReservationResponse deserializeResponse(String payload) {
        try {
            return objectMapper.readValue(payload, ReservationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize reservation response", e);
        }
    }
}
