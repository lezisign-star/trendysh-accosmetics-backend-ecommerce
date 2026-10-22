package com.nbh.backend.cart.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One product inside a kit line of the basket, as the shopper has it: what will be packed (their pick, or what the kit
 * comes with) and how many one kit holds. {@code changed} is true when it is not the kit's own variant; {@code available}
 * is false when that variant can no longer be bought, which blocks checkout for the line.
 */
public record KitPartDto(
        /** The variant that will be packed: the pick, or the default when nothing was changed. */
        UUID variantId,
        /** The variant the kit comes with; the key a pick is stored under. */
        UUID defaultVariantId,
        String productName,
        /** The colour/size on its own ("Ruby Red", "50ml"); null for a one-option product. */
        String optionLabel,
        String optionColor,
        String optionSize,
        /** The colour's dot "#RRGGBB" when it has one. */
        String swatchHex,
        /** How many of it ONE kit holds (the line's quantity multiplies it). */
        BigDecimal qtyPerKit,
        String imageUrl,
        boolean changed,
        boolean available
) {}
