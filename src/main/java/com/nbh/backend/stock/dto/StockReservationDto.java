package com.nbh.backend.stock.dto;

import com.nbh.backend.stock.entity.StockReservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StockReservationDto(
        UUID id,
        UUID variantId,
        UUID locationId,
        BigDecimal qty,
        String source,
        String status,
        Instant expiresAt
) {
    public static StockReservationDto from(StockReservation r) {
        return new StockReservationDto(
                r.getId(), r.getVariant().getId(), r.getLocation().getId(), r.getQty(),
                r.getSource().name(), r.getStatus().name(), r.getExpiresAt());
    }
}
