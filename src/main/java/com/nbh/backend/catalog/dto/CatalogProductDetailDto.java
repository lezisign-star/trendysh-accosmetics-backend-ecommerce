package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Product;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CatalogProductDetailDto(
        UUID id,
        String slug,
        String name,
        String brandName,
        String categoryName,
        String subcategoryName,
        String shortDescription,
        String description,
        String ingredients,
        String howToUse,
        List<CatalogImageDto> images,
        List<CatalogVariantDto> variants,
        double avgRating,
        long reviewCount,
        BigDecimal unitsSold
) {
    public static CatalogProductDetailDto from(Product p, List<CatalogImageDto> images, List<CatalogVariantDto> variants,
                                                double avgRating, long reviewCount, BigDecimal unitsSold) {
        return new CatalogProductDetailDto(
                p.getId(), p.getSlug(), p.getName(),
                p.getBrand() != null ? p.getBrand().getName() : null,
                p.getCategory() != null ? p.getCategory().getName() : null,
                p.getSubcategory() != null ? p.getSubcategory().getName() : null,
                p.getShortDescription(), p.getDescription(), p.getIngredients(), p.getHowToUse(),
                images, variants, avgRating, reviewCount, unitsSold);
    }
}
