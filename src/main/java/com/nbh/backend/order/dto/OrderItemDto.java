package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.CustomerOrderItem;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemDto(
        UUID variantId,
        String sku,
        String productName,
        String variantName,
        BigDecimal qty,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        boolean wholesaleApplied,
        /** The catalogue product behind this line — what a customer review attaches to. */
        UUID productId,
        /** Which colour/size/shade was ordered, on its own ("Emerald Green"); null for a product with one option. */
        String optionLabel
) {
    public static OrderItemDto from(CustomerOrderItem item) {
        return new OrderItemDto(
                item.getVariant().getId(), item.getVariant().getSku(),
                item.getVariant().getProduct().getName(), item.getVariant().getVariantName(),
                item.getQty(), item.getUnitPrice(), item.getLineTotal(), item.isWholesaleApplied(),
                item.getVariant().getProduct().getId(), item.getVariant().shortOption());
    }
}
