package com.nbh.backend.stock.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row in the staff-facing stock valuation report — the moving-average-cost engine
 * ({@code StockPostingService.applyMovement}) has computed {@code avgCost}/{@code stockValue} correctly on
 * every {@code StockBalance} row since migration V30; nothing had ever read it back as a report before this.
 */
public record ValuationLineDto(
        UUID variantId,
        String sku,
        String variantName,
        String productName,
        BigDecimal onHandQty,
        BigDecimal avgCost,
        BigDecimal stockValue
) {}
