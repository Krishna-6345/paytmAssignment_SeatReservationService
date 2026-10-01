package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record ShowDetailResponse(
    UUID id,
    String name,
    @JsonProperty("price_paise")
    Long pricePaise,
    ShowCounts counts,
    List<SeatDetail> seats
) {}
