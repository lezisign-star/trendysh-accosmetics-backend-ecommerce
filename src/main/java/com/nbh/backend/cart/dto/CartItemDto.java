package com.nbh.backend.cart.dto;

import com.nbh.backend.cart.entity.CartItem;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code unitPrice} is what the item costs right now (the basket is re-priced every time it is read).
 * {@code previousUnitPrice} is set only on the read that noticed the price move, so the shopper can be
 * told "was KSh X, now KSh Y"; {@code available} is false once the product can no longer be bought.
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
        BigDecimal previousUnitPrice
) {
    public static CartItemDto from(CartItem item) {
        return from(item, true, null);
    }

    public static CartItemDto from(CartItem item, boolean available, BigDecimal previousUnitPrice) {
        return new CartItemDto(
                item.getId(), item.getVariant().getId(),
                item.getVariant().getProduct().getId(), item.getVariant().getProduct().getSlug(),
                item.getVariant().getSku(),
                item.getProductNameSnapshot(), item.getVariantNameSnapshot(),
                item.getQty(), item.getUnitPriceSnapshot(), item.getLineTotal(),
                available, previousUnitPrice);
    }
}
