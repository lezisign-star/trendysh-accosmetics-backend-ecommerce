package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Product;

import java.util.List;
import java.util.UUID;

/** A product as staff see it: everything the editor needs, including the full text fields and per-variant stock. */
public record ProductDto(
        UUID id,
        String slug,
        String name,
        UUID brandId,
        String brandName,
        UUID categoryId,
        String categoryName,
        UUID subcategoryId,
        String subcategoryName,
        String shortDescription,
        String description,
        String ingredients,
        String howToUse,
        boolean isPublished,
        boolean deleted,
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
                p.getSubcategory() != null ? p.getSubcategory().getId() : null,
                p.getSubcategory() != null ? p.getSubcategory().getName() : null,
                p.getShortDescription(), p.getDescription(), p.getIngredients(), p.getHowToUse(),
                p.isPublished(), p.isDeleted(), primaryImageUrl, variants);
    }
}
