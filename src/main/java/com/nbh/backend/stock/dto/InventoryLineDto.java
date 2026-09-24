package com.nbh.backend.stock.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row in the staff-facing "stock levels across the catalogue" view —
 * the gap the completeness tour found: {@code GET /stock/balance} requires
 * already knowing a variant ID, with no way to just browse.
 * {@code availableQty} mirrors {@code StockBalance.getAvailableQty()}
 * (on-hand minus reserved, computed here rather than stored, same reasoning).
 * There is only one shop now, so no location/branch columns.
 */
public record InventoryLineDto(
        UUID variantId,
        String sku,
        String variantName,
        String productName,
        BigDecimal onHandQty,
        BigDecimal reservedQty,
        BigDecimal availableQty,
        BigDecimal reorderLevel,
        boolean belowReorderLevel
) {}
