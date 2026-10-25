package com.nbh.backend.packages.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A package's own page in the shop (owner, 2026-10-19): the package with its thumbnail, price and savings, and for every product
 * in it enough to show a compact details panel without leaving the page: its photos, what it is, how it is used. The
 * Packages list ({@link PackagePublicDto}) stays small; this is fetched when one package is opened.
 *
 * <p>{@code images} are the package's own photos, the thumbnail (a photo of the professional at work) first. An item's
 * {@code inStock} says whether the shelf holds the quantity ONE package uses of it; {@code wholePackages} is how many whole
 * packages the shelf can make up (what the cart and checkout will allow).
 *
 * <p>An item the shopper may change ({@code canChange}) lists the {@code choices}: the colours and sizes of that product
 * that can be bought, the one the package comes with marked {@code isDefault}. A choice's {@code priceDelta} is what ONE package
 * costs more (or less) with it instead of the default: the quantity the package holds times the difference in selling price,
 * times the package's share of its products' price (its discount is a percentage), in whole shillings
 * (see {@code PackageService#priceDelta}).
 */
public record PackageDetailDto(
        UUID variantId, UUID productId, String slug, String name,
        UUID professionId, String professionLabel, String professionSlug,
        String description, String imageUrl, List<String> images,
        BigDecimal packagePrice, BigDecimal sumOfComponents, BigDecimal savings,
        boolean inStock, BigDecimal wholePackages,
        List<Item> items
) {
    public record Item(
            UUID componentId, UUID variantId, UUID productId, String productSlug,
            String name,
            /** The colour / size / shade this item is ("Ruby Red / 15ml"); null for a one-option product. */
            String variantLabel,
            String imageUrl, List<String> images,
            BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal,
            String shortDescription, String description, String howToUse, String ingredients,
            boolean inStock,
            /** True when the shopper may pick another colour or size of this product (staff allow it and there is one). */
            boolean canChange,
            /** The options to pick from, including the default; empty unless {@code canChange}. */
            List<Choice> choices
    ) {}

    /** One colour/size of an item's product that can be bought; {@code variantId} is what a pick sends back. */
    public record Choice(
            UUID variantId,
            /** "Ruby Red", "50ml" or "Ruby Red / 50ml" as the shop names the option. */
            String label,
            String optionColor, String optionSize,
            /** The colour's dot "#RRGGBB" when it has one. */
            String swatchHex,
            BigDecimal unitPrice,
            /** What one package costs more (or less) with this option than with the default: qty x (price - default price). */
            BigDecimal priceDelta,
            String imageUrl, List<String> images,
            /** The shelf holds the quantity one package uses of this option. */
            boolean inStock,
            boolean isDefault
    ) {}
}
