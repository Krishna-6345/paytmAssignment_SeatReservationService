package com.paytm.reservation.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ReservationStatusConverter implements AttributeConverter<ReservationStatus, String> {

    @Override
    public String convertToDatabaseColumn(ReservationStatus attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getValue(); // Returns "confirmed", "cancelled" (lowercase)
    }

    @Override
    public ReservationStatus convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return ReservationStatus.fromValue(dbData);
    }
}
