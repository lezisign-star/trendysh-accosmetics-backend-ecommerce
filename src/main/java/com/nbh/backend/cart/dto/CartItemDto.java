package com.nbh.backend.cart.dto;

import com.nbh.backend.cart.entity.CartItem;

import java.math.BigDecimal;
import java.util.UUID;

public record CartItemDto(
        UUID id,
        UUID variantId,
        String sku,
        String productName,
        String variantName,
        BigDecimal qty,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
    public static CartItemDto from(CartItem item) {
        return new CartItemDto(
                item.getId(), item.getVariant().getId(), item.getVariant().getSku(),
                item.getProductNameSnapshot(), item.getVariantNameSnapshot(),
                item.getQty(), item.getUnitPriceSnapshot(), item.getLineTotal());
    }
}
