package com.paytm.reservation.exception;

import org.springframework.http.HttpStatus;

public class IdempotencyConflictException extends SeatReservationException {

    public IdempotencyConflictException(String message) {
        super(message, HttpStatus.CONFLICT, "idempotent_replay");
    }
}
