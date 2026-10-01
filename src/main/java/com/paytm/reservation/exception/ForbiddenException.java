package com.paytm.reservation.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends SeatReservationException {

    public ForbiddenException(String message) {
        super(message, HttpStatus.FORBIDDEN, "forbidden");
    }
}
