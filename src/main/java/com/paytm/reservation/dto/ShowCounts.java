package com.paytm.reservation.dto;

public record ShowCounts(
    long available,
    long held,
    long confirmed,
    long total
) {}
