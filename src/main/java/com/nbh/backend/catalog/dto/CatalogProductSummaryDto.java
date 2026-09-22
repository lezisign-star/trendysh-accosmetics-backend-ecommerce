package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.service.EffectivePriceResolver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One card in a storefront product grid — deliberately lighter than {@link CatalogProductDetailDto}.
 *
 * {@code minPrice}/{@code maxPrice} are the EFFECTIVE price range (after any deal or active
 * campaign — what the shopper actually pays), matching what {@code CartService} locks in.
 * {@code originalMinPrice} is the plain selling price, for the strikethrough — only meaningfully
 * different from {@code minPrice} when {@code onDeal} is true. {@code dealEndsAt} is the soonest
 * deadline among this product's on-deal variants (product deal or campaign), or null.
 */
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
        BigDecimal originalMinPrice,
        boolean onDeal,
        Instant dealEndsAt,
        boolean isPopular,
        boolean isNewArrival,
        double avgRating,
        long reviewCount,
        BigDecimal unitsSold,
        boolean inStock
) {
    public static CatalogProductSummaryDto from(Product p, List<EffectivePriceResolver.EffectivePrice> prices,
                                                 String primaryImageUrl, double avgRating, long reviewCount,
                                                 BigDecimal unitsSold, boolean inStock) {
        BigDecimal min = prices.stream().map(EffectivePriceResolver.EffectivePrice::effectivePrice)
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = prices.stream().map(EffectivePriceResolver.EffectivePrice::effectivePrice)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal originalMin = prices.stream().map(EffectivePriceResolver.EffectivePrice::originalPrice)
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        boolean onDeal = prices.stream().anyMatch(EffectivePriceResolver.EffectivePrice::onDeal);
        Instant dealEndsAt = prices.stream()
                .filter(EffectivePriceResolver.EffectivePrice::onDeal)
                .map(EffectivePriceResolver.EffectivePrice::dealEndsAt)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        return new CatalogProductSummaryDto(
                p.getId(), p.getSlug(), p.getName(),
                p.getBrand() != null ? p.getBrand().getName() : null,
                p.getCategory() != null ? p.getCategory().getId() : null,
                p.getCategory() != null ? p.getCategory().getName() : null,
                p.getSubcategory() != null ? p.getSubcategory().getId() : null,
                p.getSubcategory() != null ? p.getSubcategory().getName() : null,
                p.getShortDescription(), primaryImageUrl, min, max, originalMin, onDeal, dealEndsAt,
                p.isPopular(), p.isNewArrival(), avgRating, reviewCount, unitsSold, inStock);
    }
}
