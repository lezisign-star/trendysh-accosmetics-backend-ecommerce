package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Category;

import java.util.UUID;

/**
 * A category or subcategory as staff see it. {@code productCount} counts live (not deleted) products
 * filed under it; a top-level category also counts products filed in its subcategories.
 */
public record CategoryDto(
        UUID id,
        String slug,
        String name,
        UUID parentId,
        String imageUrl,
        int sortOrder,
        boolean isPublished,
        long productCount
) {
    public static CategoryDto from(Category c) {
        return from(c, 0);
    }

    public static CategoryDto from(Category c, long productCount) {
        return new CategoryDto(c.getId(), c.getSlug(), c.getName(),
                c.getParent() != null ? c.getParent().getId() : null,
                c.getImageUrl(), c.getSortOrder(), c.isPublished(), productCount);
    }
}
