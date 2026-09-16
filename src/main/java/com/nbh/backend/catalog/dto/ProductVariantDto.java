package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductVariant;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductVariantDto(
        UUID id,
        String sku,
        String variantName,
        BigDecimal sellingPrice,
        BigDecimal compareAtPrice,
        BigDecimal standardCost,
        String uom,
        BigDecimal vatRate,
        boolean vatExempt,
        BigDecimal reorderLevel,
        boolean isActive
) {
    public static ProductVariantDto from(ProductVariant v) {
        return new ProductVariantDto(
                v.getId(), v.getSku(), v.getVariantName(), v.getSellingPrice(), v.getCompareAtPrice(),
                v.getStandardCost(), v.getUom(), v.getVatRate(), v.isVatExempt(), v.getReorderLevel(), v.isActive());
    }
}
