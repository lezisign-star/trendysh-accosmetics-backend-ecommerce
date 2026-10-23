package com.nbh.backend.kits.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The "Shop by profession" modal's shape — richer than a normal catalog card (it needs the
 * itemised contents, the sum-if-bought-separately figure, and the savings), unlike the general
 * shop grid, where a kit's own variant already renders as a normal {@code CatalogVariantCardDto}
 * with no changes needed there.
 *
 * <p>{@code sumOfComponents} and each item's prices are what the products cost bought on their own today (a deal or the
 * running campaign included), so {@code savings} is never claimed against a price nobody pays. {@code pickToOrder}: out
 * of stock as it comes, but every product that is off the shelf can be swapped on the kit's page for an option that is on
 * it, so the card says "pick another option" instead of "out of stock".
 */
public record KitPublicDto(
        UUID variantId, UUID productId, String productSlug, String name,
        UUID professionId, String professionLabel, String professionSlug,
        String description, String imageUrl,
        BigDecimal packagePrice, BigDecimal sumOfComponents, BigDecimal savings, boolean inStock,
        boolean pickToOrder, List<Item> items
) {
    public record Item(UUID variantId, String name, String imageUrl,
                        BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal) {}
}
