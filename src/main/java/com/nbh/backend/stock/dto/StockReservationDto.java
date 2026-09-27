package com.nbh.backend.stock.dto;

import com.nbh.backend.stock.entity.StockReservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StockReservationDto(
        UUID id,
        UUID variantId,
        String sku,
        String variantName,
        String productName,
        BigDecimal qty,
        BigDecimal outstandingQty,
        String source,
        String status,
        String referenceType,
        UUID referenceId,
        Instant createdAt,
        Instant expiresAt
) {
    public static StockReservationDto from(StockReservation r) {
        var variant = r.getVariant();
        return new StockReservationDto(
                r.getId(), variant.getId(), variant.getSku(), variant.getVariantName(),
                variant.getProduct().getName(), r.getQty(), r.getOutstandingQty(),
                r.getSource().name(), r.getStatus().name(),
                r.getReferenceType(), r.getReferenceId(), r.getCreatedAt(), r.getExpiresAt());
    }
}
