package com.nbh.backend.cart.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One product inside a package line of the basket, as the shopper has it: what will be packed (their pick, or what the package
 * comes with) and how many one package holds. {@code changed} is true when it is not the package's own variant; {@code available}
 * is false when that variant can no longer be bought, which blocks checkout for the line.
 */
public record PackagePartDto(
        /** The variant that will be packed: the pick, or the default when nothing was changed. */
        UUID variantId,
        /** The variant the package comes with; the key a pick is stored under. */
        UUID defaultVariantId,
        String productName,
        /** The colour/size on its own ("Ruby Red", "50ml"); null for a one-option product. */
        String optionLabel,
        String optionColor,
        String optionSize,
        /** The colour's dot "#RRGGBB" when it has one. */
        String swatchHex,
        /** How many of it ONE package holds (the line's quantity multiplies it). */
        BigDecimal qtyPerPackage,
        String imageUrl,
        boolean changed,
        boolean available
) {}
