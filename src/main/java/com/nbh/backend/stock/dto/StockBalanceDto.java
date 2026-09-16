package com.nbh.backend.stock.dto;

import com.nbh.backend.stock.entity.StockBalance;

import java.math.BigDecimal;
import java.util.UUID;

public record StockBalanceDto(
        UUID variantId,
        String sku,
        UUID locationId,
        String locationCode,
        BigDecimal onHandQty,
        BigDecimal reservedQty,
        BigDecimal availableQty,
        BigDecimal avgCost
) {
    public static StockBalanceDto from(StockBalance b) {
        return new StockBalanceDto(
                b.getVariant().getId(), b.getVariant().getSku(),
                b.getLocation().getId(), b.getLocation().getCode(),
                b.getOnHandQty(), b.getReservedQty(), b.getAvailableQty(), b.getAvgCost());
    }
}
