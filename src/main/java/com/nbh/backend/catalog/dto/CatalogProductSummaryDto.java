package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Product;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One card in a storefront product grid — deliberately lighter than {@link CatalogProductDetailDto}. */
public record CatalogProductSummaryDto(
        UUID id,
        String slug,
        String name,
        String brandName,
        UUID categoryId,
        String categoryName,
        UUID subcategoryId,
        String subcategoryName,
        String shortDescription,
        String primaryImageUrl,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        double avgRating,
        long reviewCount,
        BigDecimal unitsSold,
        boolean inStock
) {
    public static CatalogProductSummaryDto from(Product p, List<BigDecimal> variantPrices, String primaryImageUrl,
                                                 double avgRating, long reviewCount, BigDecimal unitsSold,
                                                 boolean inStock) {
        BigDecimal min = variantPrices.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = variantPrices.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        return new CatalogProductSummaryDto(
                p.getId(), p.getSlug(), p.getName(),
                p.getBrand() != null ? p.getBrand().getName() : null,
                p.getCategory() != null ? p.getCategory().getId() : null,
                p.getCategory() != null ? p.getCategory().getName() : null,
                p.getSubcategory() != null ? p.getSubcategory().getId() : null,
                p.getSubcategory() != null ? p.getSubcategory().getName() : null,
                p.getShortDescription(), primaryImageUrl, min, max, avgRating, reviewCount, unitsSold,
                inStock);
    }
}
