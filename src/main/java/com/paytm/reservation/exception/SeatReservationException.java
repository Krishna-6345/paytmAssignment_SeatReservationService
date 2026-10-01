package com.paytm.reservation.exception;

import org.springframework.http.HttpStatus;

public class SeatReservationException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;

    public SeatReservationException(String message, HttpStatus status, String reason) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }
}
