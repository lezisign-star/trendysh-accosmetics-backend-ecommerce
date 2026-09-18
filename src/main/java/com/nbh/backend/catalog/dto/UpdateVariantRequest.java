package com.nbh.backend.catalog.dto;

/**
 * Partial update — every field is optional, only non-null ones are applied.
 * {@code sku} is deliberately not here: a SKU is treated as immutable once
 * created (deactivate and create a new variant instead of renumbering one
 * that may already be on a real stock document or order line).
 */
public record UpdateVariantRequest(
        String variantName,
        String sellingPrice,
        String compareAtPrice,
        String standardCost,
        String uom,
        String vatRate,
        Boolean vatExempt,
        String reorderLevel,
        Boolean isActive
) {}
