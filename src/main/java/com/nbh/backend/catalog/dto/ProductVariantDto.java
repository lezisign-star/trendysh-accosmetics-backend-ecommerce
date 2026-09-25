package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductVariant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code availableQty} is what can be sold right now (on hand minus reserved, across physical
 * locations). It is filled in by the admin list/detail endpoints and null elsewhere (e.g. right
 * after creating a variant, where it is simply zero).
 *
 * {@code dealPrice}/{@code dealEndsAt} are the raw stored values, not resolved through
 * {@code EffectivePriceResolver} — this is the admin/staff view of the variant, which needs to see
 * and edit exactly what's set, including an already-expired deal it forgot to clear.
 */
public record ProductVariantDto(
        UUID id,
        String sku,
        String variantName,
        String description,
        int sortOrder,
        BigDecimal sellingPrice,
        BigDecimal compareAtPrice,
        BigDecimal standardCost,
        String uom,
        BigDecimal vatRate,
        boolean vatExempt,
        BigDecimal reorderLevel,
        boolean isActive,
        BigDecimal availableQty,
        BigDecimal dealPrice,
        Instant dealEndsAt
) {
    public static ProductVariantDto from(ProductVariant v) {
        return from(v, null);
    }

    public static ProductVariantDto from(ProductVariant v, BigDecimal availableQty) {
        return new ProductVariantDto(
                v.getId(), v.getSku(), v.getVariantName(), v.getDescription(), v.getSortOrder(),
                v.getSellingPrice(), v.getCompareAtPrice(),
                v.getStandardCost(), v.getUom(), v.getVatRate(), v.isVatExempt(), v.getReorderLevel(), v.isActive(),
                availableQty, v.getDealPrice(), v.getDealEndsAt());
    }
}
