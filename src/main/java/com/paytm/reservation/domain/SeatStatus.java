package com.paytm.reservation.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SeatStatus {
    AVAILABLE("available"),
    HELD("held"),
    CONFIRMED("confirmed");

    private final String value;

    SeatStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SeatStatus fromValue(String value) {
        for (SeatStatus status : SeatStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown seat status: " + value);
    }
}
