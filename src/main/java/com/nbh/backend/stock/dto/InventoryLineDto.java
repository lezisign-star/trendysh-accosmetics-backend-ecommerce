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
 *
 * <p>{@code productId} is what the "set stock" call needs next to the variant id, so the list can edit a row
 * in place; {@code isPackage} marks a variant that holds no stock of its own (its parts do), which the list shows
 * but cannot edit.
 */
public record InventoryLineDto(
        UUID variantId,
        UUID productId,
        String sku,
        String variantName,
        String productName,
        BigDecimal onHandQty,
        BigDecimal reservedQty,
        BigDecimal availableQty,
        BigDecimal reorderLevel,
        boolean belowReorderLevel,
        boolean isPackage,
        /** Where its number comes from (V78): NONE, ESTIMATED or COUNTED. */
        com.nbh.backend.catalog.entity.StockBasis stockBasis,
        /** The product's own "sell without stock" switch (V78). */
        boolean productSellWithoutStock,
        /** Not tracked yet: no number and a "sell without stock" switch covers it, so customers can order it freely. */
        boolean untracked
) {
    /** What the stock list query builds; {@code untracked} is worked out afterwards (it depends on the shop switch). */
    public InventoryLineDto(UUID variantId, UUID productId, String sku, String variantName, String productName,
                            BigDecimal onHandQty, BigDecimal reservedQty, BigDecimal availableQty, BigDecimal reorderLevel,
                            boolean belowReorderLevel, boolean isPackage, com.nbh.backend.catalog.entity.StockBasis stockBasis,
                            boolean productSellWithoutStock) {
        this(variantId, productId, sku, variantName, productName, onHandQty, reservedQty, availableQty, reorderLevel,
                belowReorderLevel, isPackage, stockBasis, productSellWithoutStock, false);
    }

    public InventoryLineDto withUntracked(boolean value) {
        return new InventoryLineDto(variantId, productId, sku, variantName, productName, onHandQty, reservedQty, availableQty,
                reorderLevel, belowReorderLevel, isPackage, stockBasis, productSellWithoutStock, value);
    }
}
