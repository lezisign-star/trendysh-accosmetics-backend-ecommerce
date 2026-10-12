package com.nbh.backend.catalog.dto;

import java.util.List;
import java.util.UUID;

/**
 * A variant's own product-detail page (Phase 3, 2026-09-24). {@code siblings} is every sellable
 * variant of the same product, INCLUDING this one, in display order — the storefront's "also
 * available" strip; the frontend picks out which entry is "current" by matching {@code variantId}.
 * {@code aboutProduct} is the product's own description (shared by every variant); {@code description}
 * is this variant's own, may be blank.
 */
public record CatalogVariantDetailDto(
        CatalogVariantCardDto card,
        String description,
        String aboutProduct,
        String ingredients,
        String howToUse,
        List<CatalogImageDto> images,
        List<CatalogVariantCardDto> siblings,
        UUID primaryCategoryId,
        String primaryCategoryName,
        String primaryCategorySlug,
        UUID primaryCategoryParentId,
        String primaryCategoryParentName,
        String primaryCategoryParentSlug,
        /** The product page's bullet list, one entry per point; empty when none was written. */
        List<String> highlights,
        /** What a search result shows for this product; null when none was written (the page then falls back to its name). */
        String seoTitle,
        String seoDescription,
        /**
         * The product's colours in the picker's order, only those at least one sellable variant has (V63). Built from
         * {@code siblings} for a product with no stored list. Empty = the product has no colours.
         */
        List<CatalogOptionDto> colors,
        /** The product's sizes in order, same rules. Empty = no sizes. */
        List<CatalogOptionDto> sizes
) {}
