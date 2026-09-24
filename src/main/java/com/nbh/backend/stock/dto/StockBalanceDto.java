package com.nbh.backend.stock.dto;

import com.nbh.backend.stock.entity.StockBalance;

import java.math.BigDecimal;
import java.util.UUID;

public record StockBalanceDto(
        UUID variantId,
        String sku,
        String account,
        BigDecimal onHandQty,
        BigDecimal reservedQty,
        BigDecimal availableQty,
        BigDecimal avgCost
) {
    public static StockBalanceDto from(StockBalance b) {
        return new StockBalanceDto(
                b.getVariant().getId(), b.getVariant().getSku(), b.getAccount().name(),
                b.getOnHandQty(), b.getReservedQty(), b.getAvailableQty(), b.getAvgCost());
    }
}
