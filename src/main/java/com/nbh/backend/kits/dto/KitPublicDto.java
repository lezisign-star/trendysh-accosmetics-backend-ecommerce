package com.nbh.backend.kits.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The "Shop by profession" modal's shape — richer than a normal catalog card (it needs the
 * itemised contents, the sum-if-bought-separately figure, and the savings), unlike the general
 * shop grid, where a kit's own variant already renders as a normal {@code CatalogVariantCardDto}
 * with no changes needed there.
 */
public record KitPublicDto(
        UUID variantId, UUID productId, String productSlug, String name,
        UUID professionId, String professionLabel, String professionSlug,
        String description, String imageUrl,
        BigDecimal packagePrice, BigDecimal sumOfComponents, BigDecimal savings, boolean inStock,
        List<Item> items
) {
    public record Item(UUID variantId, String name, String imageUrl,
                        BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal) {}
}
