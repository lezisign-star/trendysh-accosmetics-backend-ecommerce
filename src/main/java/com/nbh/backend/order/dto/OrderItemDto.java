package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.CustomerOrderItem;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What was bought is described from what the line froze when the order was placed (see
 * {@link CustomerOrderItem#rememberVariant}), so renaming or re-optioning a variant later never changes an old order.
 * An order placed before that is described from the live variant instead.
 */
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
        String optionLabel,
        /** The ordered colour on its own, and the colour's dot ("#RRGGBB"); null when the variant has none. */
        String optionColor,
        String swatchHex,
        /** The ordered size on its own ("50ml"); null when the variant has none. */
        String optionSize,
        /** The photo the variant had when it was ordered; null for an order placed before photos were frozen. */
        String imageUrl,
        UUID id,
        /**
         * Set on a line that is one of the products inside a kit: the id of the kit's own line. The kit's line carries the
         * price (and is the one the order's total adds up); its products are listed under it, as the shopper picked them.
         */
        UUID kitLineId
) {
    public static OrderItemDto from(CustomerOrderItem item) {
        return new OrderItemDto(
                item.getVariant().getId(), item.soldSku(),
                item.soldProductName(), item.soldVariantName(),
                item.getQty(), item.getUnitPrice(), item.getLineTotal(), item.isWholesaleApplied(),
                item.getVariant().getProduct().getId(), item.soldOptionLabel(),
                item.soldOptionColor(), item.soldSwatchHex(), item.soldOptionSize(), item.soldImageUrl(),
                item.getId(), item.getParentItem() == null ? null : item.getParentItem().getId());
    }
}
