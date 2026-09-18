package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Product;

import java.util.List;
import java.util.UUID;

public record ProductDto(
        UUID id,
        String slug,
        String name,
        UUID brandId,
        String brandName,
        UUID categoryId,
        String categoryName,
        String shortDescription,
        String description,
        boolean isPublished,
        String primaryImageUrl,
        List<ProductVariantDto> variants
) {
    public static ProductDto from(Product p, List<ProductVariantDto> variants) {
        return from(p, variants, null);
    }

    public static ProductDto from(Product p, List<ProductVariantDto> variants, String primaryImageUrl) {
        return new ProductDto(
                p.getId(), p.getSlug(), p.getName(),
                p.getBrand() != null ? p.getBrand().getId() : null,
                p.getBrand() != null ? p.getBrand().getName() : null,
                p.getCategory() != null ? p.getCategory().getId() : null,
                p.getCategory() != null ? p.getCategory().getName() : null,
                p.getShortDescription(), p.getDescription(), p.isPublished(), primaryImageUrl, variants);
    }
}
