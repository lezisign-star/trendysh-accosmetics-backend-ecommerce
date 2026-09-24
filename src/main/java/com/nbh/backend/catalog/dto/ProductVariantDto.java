package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.VariantAttributeValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code availableQty} is what can be sold right now (on hand minus reserved, across physical
 * locations). It is filled in by the admin list/detail endpoints and null elsewhere (e.g. right
 * after creating a variant, where it is simply zero).
 *
 * {@code dealPrice}/{@code dealEndsAt} are the raw stored values, not resolved through
 * {@code EffectivePriceResolver} — this is the admin/staff view of the variant, which needs to see
 * and edit exactly what's set, including an already-expired deal it forgot to clear.
 *
 * {@code attributes} mirrors {@code CatalogVariantDto.AttributeValueSummary} (Color: Red, swatch
 * #C0392B) — set once at variant creation and immutable after, same as {@code sku}.
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
        Instant dealEndsAt,
        // Always empty as of Phase 3 (2026-09-24) — the attribute/option model is gone from the
        // editor. Compatibility for the pre-Phase-3 frontend; removed in Phase 3f.
        List<AttributeValueSummary> attributes
) {
    public record AttributeValueSummary(
            String attributeCode,
            String attributeName,
            String value,
            String displayValue,
            String swatchHex
    ) {}

    public static ProductVariantDto from(ProductVariant v) {
        return from(v, null, List.of());
    }

    public static ProductVariantDto from(ProductVariant v, BigDecimal availableQty) {
        return from(v, availableQty, List.of());
    }

    public static ProductVariantDto from(ProductVariant v, BigDecimal availableQty,
                                          List<VariantAttributeValue> attributeValues) {
        List<AttributeValueSummary> attrs = attributeValues.stream()
                .filter(vav -> vav.getVariant().getId().equals(v.getId()))
                .map(vav -> new AttributeValueSummary(
                        vav.getAttributeValue().getAttribute().getCode(),
                        vav.getAttributeValue().getAttribute().getName(),
                        vav.getAttributeValue().getValue(),
                        vav.getAttributeValue().getDisplayValue(),
                        vav.getAttributeValue().getSwatchHex()))
                .toList();
        return new ProductVariantDto(
                v.getId(), v.getSku(), v.getVariantName(), v.getDescription(), v.getSortOrder(),
                v.getSellingPrice(), v.getCompareAtPrice(),
                v.getStandardCost(), v.getUom(), v.getVatRate(), v.isVatExempt(), v.getReorderLevel(), v.isActive(),
                availableQty, v.getDealPrice(), v.getDealEndsAt(), attrs);
    }
}
