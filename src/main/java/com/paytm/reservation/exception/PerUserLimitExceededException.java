package com.paytm.reservation.exception;

import org.springframework.http.HttpStatus;

public class PerUserLimitExceededException extends SeatReservationException {

    public PerUserLimitExceededException(String message) {
        super(message, HttpStatus.CONFLICT, "per_user_limit");
    }
}
