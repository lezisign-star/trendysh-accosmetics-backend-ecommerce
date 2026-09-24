package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A product as staff see it: everything the editor needs, including the full text fields and per-variant stock. */
public record ProductDto(
        UUID id,
        String slug,
        String name,
        UUID brandId,
        String brandName,
        List<CategoryRef> categories,
        // Compatibility for the pre-Phase-3 frontend — the primary category (position 0), and its
        // parent when the primary is itself a subcategory. Removed in Phase 3f.
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
        boolean isPopular,
        boolean isNewArrival,
        String primaryImageUrl,
        Instant updatedAt,
        List<String> warnings,
        List<ProductVariantDto> variants
) {
    public record CategoryRef(UUID id, String name, UUID parentId, String parentName) {
        static CategoryRef from(Category c) {
            Category parent = c.getParent();
            return new CategoryRef(c.getId(), c.getName(),
                    parent != null ? parent.getId() : null, parent != null ? parent.getName() : null);
        }
    }

    public static ProductDto from(Product p, List<ProductVariantDto> variants) {
        return from(p, variants, null, List.of());
    }

    public static ProductDto from(Product p, List<ProductVariantDto> variants, String primaryImageUrl) {
        return from(p, variants, primaryImageUrl, List.of());
    }

    public static ProductDto from(Product p, List<ProductVariantDto> variants, String primaryImageUrl, List<String> warnings) {
        List<CategoryRef> categories = p.getCategories().stream().map(CategoryRef::from).toList();
        CategoryRef primary = categories.isEmpty() ? null : categories.get(0);
        UUID categoryId = primary == null ? null : (primary.parentId() != null ? primary.parentId() : primary.id());
        String categoryName = primary == null ? null : (primary.parentId() != null ? primary.parentName() : primary.name());
        UUID subcategoryId = primary != null && primary.parentId() != null ? primary.id() : null;
        String subcategoryName = primary != null && primary.parentId() != null ? primary.name() : null;

        return new ProductDto(
                p.getId(), p.getSlug(), p.getName(),
                p.getBrand() != null ? p.getBrand().getId() : null,
                p.getBrand() != null ? p.getBrand().getName() : null,
                categories, categoryId, categoryName, subcategoryId, subcategoryName,
                p.getShortDescription(), p.getDescription(), p.getIngredients(), p.getHowToUse(),
                p.isPublished(), p.isDeleted(), p.isPopular(), p.isNewArrival(), primaryImageUrl,
                p.getUpdatedAt(), warnings, variants);
    }
}
