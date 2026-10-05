package com.nbh.backend.cart.dto;

import com.nbh.backend.cart.entity.CartItem;

import java.math.BigDecimal;
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
        BigDecimal wholesalePrice
) {
    public static CartItemDto from(CartItem item) {
        return from(item, true, null, null);
    }

    public static CartItemDto from(CartItem item, boolean available, BigDecimal stockLeft,
                                   BigDecimal previousUnitPrice) {
        return new CartItemDto(
                item.getId(), item.getVariant().getId(),
                item.getVariant().getProduct().getId(), item.getVariant().getProduct().getSlug(),
                item.getVariant().getSku(),
                item.getProductNameSnapshot(), item.getVariantNameSnapshot(),
                item.getQty(), item.getUnitPriceSnapshot(), item.getLineTotal(),
                available, stockLeft, previousUnitPrice, item.isWholesaleApplied(),
                item.getVariant().getWholesaleMinQty(), item.getVariant().getWholesalePrice());
    }
}
