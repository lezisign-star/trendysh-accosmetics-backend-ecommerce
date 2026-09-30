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
        boolean wholesaleApplied
) {
    public static OrderItemDto from(CustomerOrderItem item) {
        return new OrderItemDto(
                item.getVariant().getId(), item.getVariant().getSku(),
                item.getVariant().getProduct().getName(), item.getVariant().getVariantName(),
                item.getQty(), item.getUnitPrice(), item.getLineTotal(), item.isWholesaleApplied());
    }
}
