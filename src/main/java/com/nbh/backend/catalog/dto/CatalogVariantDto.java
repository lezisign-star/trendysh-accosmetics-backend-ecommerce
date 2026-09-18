package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.VariantAttributeValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Customer-facing variant view — adds resolved attribute values (Shade: Red, swatch #C0392B) the staff DTO doesn't need. */
public record CatalogVariantDto(
        UUID id,
        String sku,
        String variantName,
        BigDecimal sellingPrice,
        BigDecimal compareAtPrice,
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

    public static CatalogVariantDto from(ProductVariant v, List<VariantAttributeValue> attributeValues, boolean inStock) {
        List<AttributeValueSummary> attrs = attributeValues.stream()
                .filter(vav -> vav.getVariant().getId().equals(v.getId()))
                .map(vav -> new AttributeValueSummary(
                        vav.getAttributeValue().getAttribute().getCode(),
                        vav.getAttributeValue().getAttribute().getName(),
                        vav.getAttributeValue().getValue(),
                        vav.getAttributeValue().getDisplayValue(),
                        vav.getAttributeValue().getSwatchHex()))
                .toList();
        return new CatalogVariantDto(v.getId(), v.getSku(), v.getVariantName(), v.getSellingPrice(),
                v.getCompareAtPrice(), v.getUom(), inStock, attrs);
    }
}
