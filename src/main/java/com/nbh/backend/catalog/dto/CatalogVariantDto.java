package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.VariantAttributeValue;
import com.nbh.backend.catalog.service.EffectivePriceResolver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Customer-facing variant view — adds resolved attribute values (Shade: Red, swatch #C0392B) the
 * staff DTO doesn't need. {@code sellingPrice} is the EFFECTIVE price (after any deal/campaign —
 * what this variant actually costs right now); {@code compareAtPrice} keeps its existing meaning
 * (a merchant-entered "was" price, independent of deals) and {@code originalPrice}/{@code onDeal}/
 * {@code dealEndsAt} are new, describing the deal itself when one applies.
 */
public record CatalogVariantDto(
        UUID id,
        String sku,
        String variantName,
        BigDecimal sellingPrice,
        BigDecimal compareAtPrice,
        BigDecimal originalPrice,
        boolean onDeal,
        Instant dealEndsAt,
        String uom,
        boolean inStock,
        List<AttributeValueSummary> attributes
) {
    public record AttributeValueSummary(
            String attributeCode,
            String attributeName,
            String value,
            String displayValue,
            String swatchHex
    ) {}

    public static CatalogVariantDto from(ProductVariant v, List<VariantAttributeValue> attributeValues,
                                          boolean inStock, EffectivePriceResolver.EffectivePrice price) {
        List<AttributeValueSummary> attrs = attributeValues.stream()
                .filter(vav -> vav.getVariant().getId().equals(v.getId()))
                .map(vav -> new AttributeValueSummary(
                        vav.getAttributeValue().getAttribute().getCode(),
                        vav.getAttributeValue().getAttribute().getName(),
                        vav.getAttributeValue().getValue(),
                        vav.getAttributeValue().getDisplayValue(),
                        vav.getAttributeValue().getSwatchHex()))
                .toList();
        return new CatalogVariantDto(v.getId(), v.getSku(), v.getVariantName(), price.effectivePrice(),
                v.getCompareAtPrice(), price.originalPrice(), price.onDeal(), price.dealEndsAt(),
                v.getUom(), inStock, attrs);
    }
}
