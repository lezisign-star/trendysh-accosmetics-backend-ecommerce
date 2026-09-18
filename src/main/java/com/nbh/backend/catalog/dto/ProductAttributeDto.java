package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductAttribute;
import com.nbh.backend.catalog.entity.ProductAttributeValue;

import java.util.List;
import java.util.UUID;

/** The variant-builder's picklist — an attribute (Shade, Size) with its available values. */
public record ProductAttributeDto(
        UUID id,
        String code,
        String name,
        ProductAttribute.AttributeDisplayType displayType,
        List<AttributeValueDto> values
) {
    public static ProductAttributeDto from(ProductAttribute attribute, List<ProductAttributeValue> values) {
        return new ProductAttributeDto(attribute.getId(), attribute.getCode(), attribute.getName(),
                attribute.getDisplayType(), values.stream().map(AttributeValueDto::from).toList());
    }

    public record AttributeValueDto(UUID id, String value, String displayValue, String swatchHex) {
        public static AttributeValueDto from(ProductAttributeValue v) {
            return new AttributeValueDto(v.getId(), v.getValue(), v.getDisplayValue(), v.getSwatchHex());
        }
    }
}
