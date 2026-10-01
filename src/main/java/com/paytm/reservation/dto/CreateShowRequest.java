package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateShowRequest(
    @NotBlank(message = "Show name is required")
    String name,

    @NotEmpty(message = "Seats list must not be empty")
    List<String> seats,

    @NotNull(message = "Price in paise is required")
    @Min(value = 0, message = "Price in paise must be non-negative")
    @JsonProperty("price_paise")
    Long pricePaise
) {}
