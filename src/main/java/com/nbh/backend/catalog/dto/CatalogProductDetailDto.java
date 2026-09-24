package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CatalogProductDetailDto(
        UUID id,
        String slug,
        String name,
        String brandName,
        UUID categoryId,
        String categoryName,
        UUID subcategoryId,
        String subcategoryName,
        String shortDescription,
        String description,
        String ingredients,
        String howToUse,
        List<CatalogImageDto> images,
        List<CatalogVariantDto> variants,
        boolean isPopular,
        boolean isNewArrival,
        double avgRating,
        long reviewCount,
        BigDecimal unitsSold,
        boolean inStock
) {
    public static CatalogProductDetailDto from(Product p, List<CatalogImageDto> images, List<CatalogVariantDto> variants,
                                                double avgRating, long reviewCount, BigDecimal unitsSold) {
        Category primary = p.getCategories().isEmpty() ? null : p.getCategories().get(0);
        Category primaryParent = primary != null ? primary.getParent() : null;
        return new CatalogProductDetailDto(
                p.getId(), p.getSlug(), p.getName(),
                p.getBrand() != null ? p.getBrand().getName() : null,
                primaryParent != null ? primaryParent.getId() : (primary != null ? primary.getId() : null),
                primaryParent != null ? primaryParent.getName() : (primary != null ? primary.getName() : null),
                primaryParent != null ? primary.getId() : null,
                primaryParent != null ? primary.getName() : null,
                p.getShortDescription(), p.getDescription(), p.getIngredients(), p.getHowToUse(),
                images, variants, p.isPopular(), p.isNewArrival(), avgRating, reviewCount, unitsSold,
                variants.stream().anyMatch(CatalogVariantDto::inStock));
    }
}
