package com.nbh.backend.cart.dto;

import com.nbh.backend.cart.entity.CartItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code unitPrice} is what the item costs right now (the basket is re-priced every time it is read).
 * {@code previousUnitPrice} is set only on the read that noticed the price move, so the shopper can be
 * told "was KSh X, now KSh Y"; {@code available} is false once the product can no longer be bought.
 * {@code stockLeft} is how many of this line can be sold right now (for a kit, how many whole kits its
 * components can still make up): at or below zero the line is sold out, and below {@code qty} the shopper
 * is asking for more than is left. It lets the cart offer a one-tap remove before checkout refuses the order.
 *
 * {@code wholesaleApplied} is true when {@code unitPrice} is currently the variant's wholesale
 * price; {@code wholesaleMinQty}/{@code wholesalePrice} (the variant's raw tier, nullable) are
 * present even before the tier is reached, so the frontend can show "add N more to unlock KES X
 * each" ahead of time, not just once it applies.
 *
 * {@code kitItems} lists what is inside a kit line, with the shopper's picks applied (empty for any other line), and
 * {@code kitChoices} is the picks themselves (default variant id to chosen variant id), so the kit's page can be opened
 * with them already chosen. {@code imageUrl} is a kit line's own photo (the professional at work): a kit is not in the
 * shop's product list, so the storefront cannot look its photo up there; null for every other line.
 */
public record CartItemDto(
        UUID id,
        UUID variantId,
        UUID productId,
        String productSlug,
        String sku,
        String productName,
        String variantName,
        BigDecimal qty,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        boolean available,
        BigDecimal stockLeft,
        BigDecimal previousUnitPrice,
        boolean wholesaleApplied,
        Integer wholesaleMinQty,
        BigDecimal wholesalePrice,
        /** Variants v2: the colour and size picked (with the colour's dot) and the short option, so the cart and checkout
         *  show exactly which one is in the basket. The variant as it is now: the order freezes them at checkout. */
        String optionLabel,
        String optionColor,
        String optionSize,
        String swatchHex,
        List<KitPartDto> kitItems,
        java.util.Map<UUID, UUID> kitChoices,
        String imageUrl
) {
    public static CartItemDto from(CartItem item) {
        return from(item, true, null, null);
    }

    public static CartItemDto from(CartItem item, boolean available, BigDecimal stockLeft,
                                   BigDecimal previousUnitPrice) {
        return from(item, available, stockLeft, previousUnitPrice, List.of(), null);
    }

    public static CartItemDto from(CartItem item, boolean available, BigDecimal stockLeft,
                                   BigDecimal previousUnitPrice, List<KitPartDto> kitItems, String imageUrl) {
        return new CartItemDto(
                item.getId(), item.getVariant().getId(),
                item.getVariant().getProduct().getId(), item.getVariant().getProduct().getSlug(),
                item.getVariant().getSku(),
                item.getProductNameSnapshot(), item.getVariantNameSnapshot(),
                item.getQty(), item.getUnitPriceSnapshot(), item.getLineTotal(),
                available, stockLeft, previousUnitPrice, item.isWholesaleApplied(),
                item.getVariant().getWholesaleMinQty(), item.getVariant().getWholesalePrice(),
                item.getVariant().shortOption(), item.getVariant().getOptionColor(), item.getVariant().getOptionSize(),
                item.getVariant().getSwatchHex(), kitItems, new java.util.HashMap<>(item.getChoices()), imageUrl);
    }
}
