package com.nbh.backend.stock.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row in the staff-facing "stock levels across the catalogue" view —
 * the gap the completeness tour found: {@code GET /stock/balance} requires
 * already knowing a variant or location ID, with no way to just browse.
 * {@code availableQty} mirrors {@code StockBalance.getAvailableQty()}
 * (on-hand minus reserved, computed here rather than stored, same reasoning).
 */
public record InventoryLineDto(
        UUID variantId,
        String sku,
        String variantName,
        String productName,
        UUID locationId,
        String locationCode,
        UUID branchId,
        String branchName,
        BigDecimal onHandQty,
        BigDecimal reservedQty,
        BigDecimal availableQty,
        BigDecimal reorderLevel,
        boolean belowReorderLevel
) {}
