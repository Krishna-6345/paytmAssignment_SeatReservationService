package com.paytm.reservation.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends SeatReservationException {

    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND, "not_found");
    }
}
