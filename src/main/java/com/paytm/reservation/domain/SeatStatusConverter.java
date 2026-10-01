package com.paytm.reservation.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SeatStatusConverter implements AttributeConverter<SeatStatus, String> {

    @Override
    public String convertToDatabaseColumn(SeatStatus attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getValue(); // Returns "available", "held", "confirmed" (lowercase)
    }

    @Override
    public SeatStatus convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return SeatStatus.fromValue(dbData);
    }
}
