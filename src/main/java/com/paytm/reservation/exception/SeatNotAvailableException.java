package com.paytm.reservation.exception;

import org.springframework.http.HttpStatus;

public class SeatNotAvailableException extends SeatReservationException {

    public SeatNotAvailableException(String message) {
        super(message, HttpStatus.CONFLICT, "seat_taken");
    }
}
