package com.paytm.reservation.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends SeatReservationException {

    public UnauthorizedException(String message) {
        super(message, HttpStatus.UNAUTHORIZED, "unauthorized");
    }
}
