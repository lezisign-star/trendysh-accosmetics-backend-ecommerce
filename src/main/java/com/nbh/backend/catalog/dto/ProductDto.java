package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Product;

import java.util.List;
import java.util.UUID;

public record ProductDto(
        UUID id,
        String slug,
        String name,
        UUID brandId,
        UUID categoryId,
        String shortDescription,
        String description,
        boolean isPublished,
        List<ProductVariantDto> variants
) {
    public static ProductDto from(Product p, List<ProductVariantDto> variants) {
        return new ProductDto(
                p.getId(), p.getSlug(), p.getName(),
                p.getBrand() != null ? p.getBrand().getId() : null,
                p.getCategory() != null ? p.getCategory().getId() : null,
                p.getShortDescription(), p.getDescription(), p.isPublished(), variants);
    }
}
