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
 *
 * {@code availableQty} is {@link com.nbh.backend.stock.service.StockAvailabilityService}'s own
 * "SHOP on-hand minus reserved" figure (2026-09-29) — the same number {@code inStock} is already
 * derived from, just not rounded down to a boolean. Present on every card (grid, siblings, kit
 * components) but only the product-detail page renders it today; a kit's own line carries
 * {@link BigDecimal#ZERO} here since availability isn't tracked per kit, only per real variant.
 *
 * {@code sku} (2026-09-29) is now customer-facing per the client's own call — a wholesale/trade
 * buyer reordering wants a code to reference, not just a name. Was admin-only before this.
 *
 * {@code description} (2026-09-29) is the product's own description (shared by every variant,
 * same source as {@code CatalogVariantDetailDto.aboutProduct}) — added here specifically to feed
 * the frontend's client-side fuzzy search ({@code lib/search.ts}), which already had description
 * matching built in but nothing to match against (every card's description was hardcoded to "" in
 * {@code toProduct()}); not rendered anywhere on a card, search-only.
 *
 * {@code wholesaleMinQty}/{@code wholesalePrice} (nullable, present only when the variant has a
 * tier configured) advertise the tier for "buy N+ at KES X each" card messaging — unlike
 * {@code price}/{@code originalPrice} these are the RAW stored values, never resolved against a
 * cart quantity (a card has no cart-qty context). Null/null together = no tier, same convention
 * as {@code dealPrice}.
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
        BigDecimal availableQty,
        String sku,
        String description,
        int siblingCount,
        Integer wholesaleMinQty,
        BigDecimal wholesalePrice,
        /** The product's one-line summary for listings; null when none was written. */
        String shortDescription
) {
    /** True for a variant name that adds no information beyond the product name itself. */
    public static boolean isMeaninglessName(String variantName) {
        return variantName == null || variantName.isBlank() || variantName.equalsIgnoreCase("Default");
    }
}
