package com.nbh.backend.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One card in the storefront grid — Phase 3 (2026-09-24): every SELLABLE VARIANT gets its own
 * card now, not one card per product with a shade picker. {@code variantName} is null (the card
 * shows just the product name) when the product has only this one sellable variant, or the
 * variant's own name is blank or the placeholder {@code "Default"} a single-variant product gets
 * auto-assigned — same rule {@code CheckoutService} already used for order-confirmation text.
 *
 * {@code price}/{@code originalPrice}/{@code onDeal}/{@code dealEndsAt} are the EFFECTIVE price
 * (after any deal or active campaign — what the shopper actually pays), matching what
 * {@code CartService} locks in. {@code siblingCount} is how many OTHER sellable variants this
 * product has — the "+3 more" the product-detail page's sibling strip expands on.
 */
public record CatalogVariantCardDto(
        UUID variantId,
        UUID productId,
        String productSlug,
        String productName,
        String variantName,
        String displayName,
        String imageUrl,
        List<UUID> categoryIds,
        UUID primaryCategoryId,
        BigDecimal price,
        BigDecimal originalPrice,
        boolean onDeal,
        Instant dealEndsAt,
        boolean inStock,
        boolean isPopular,
        boolean isNewArrival,
        double avgRating,
        long reviewCount,
        BigDecimal unitsSold,
        int siblingCount
) {
    /** True for a variant name that adds no information beyond the product name itself. */
    public static boolean isMeaninglessName(String variantName) {
        return variantName == null || variantName.isBlank() || variantName.equalsIgnoreCase("Default");
    }
}
