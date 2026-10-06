package com.nbh.backend.stock.service;

import com.nbh.backend.catalog.entity.ProductVariant;

import java.math.BigDecimal;

/**
 * What a typed-in quantity has to look like before anything is posted or reserved with it. Found in the
 * 2026-10-06 go-live QA: a stock count of 2.5 was saved for a lipstick, and a count of a trillion reached the
 * database and came back as a baffling "conflicting record" error.
 *
 * <p>Applied to the number a person typed (a count, a delivery line, a write-off, a cart quantity), never to a
 * derived difference between two balances, so a shelf that somehow holds a fraction can still be corrected by
 * typing the right whole number.
 */
public final class QuantityRules {

    /** More than any shop shelf holds, and well inside the columns that store quantities: a bigger number is a typo. */
    public static final BigDecimal MAX_QTY = new BigDecimal("1000000");

    private QuantityRules() {}

    /**
     * @param label how to name the item in the message: its SKU for staff screens, its product name for a shopper.
     * @throws IllegalArgumentException (answered as a 400) when {@code qty} cannot be a real count of this variant.
     */
    public static void requireSensible(String label, ProductVariant variant, BigDecimal qty) {
        if (qty == null) {
            return;
        }
        if (qty.abs().compareTo(MAX_QTY) > 0) {
            throw new IllegalArgumentException(label + ": that quantity is too large");
        }
        if (countedInWholes(variant) && qty.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(label + ": the quantity must be a whole number");
        }
    }

    /** Pieces are counted in wholes; anything sold by weight or length (another unit of measure) may carry a fraction. */
    static boolean countedInWholes(ProductVariant variant) {
        String uom = variant.getUom();
        return uom == null || uom.isBlank() || "PIECE".equalsIgnoreCase(uom);
    }
}
