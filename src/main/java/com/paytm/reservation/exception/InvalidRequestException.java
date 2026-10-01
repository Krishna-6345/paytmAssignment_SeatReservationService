package com.paytm.reservation.exception;

import org.springframework.http.HttpStatus;

public class InvalidRequestException extends SeatReservationException {

    public InvalidRequestException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "invalid_request");
    }
}
