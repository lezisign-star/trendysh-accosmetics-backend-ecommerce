package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Category;

import java.util.UUID;

public record CatalogCategoryDto(UUID id, String slug, String name, String imageUrl, UUID parentId) {
    public static CatalogCategoryDto from(Category c) {
        return new CatalogCategoryDto(c.getId(), c.getSlug(), c.getName(), c.getImageUrl(),
                c.getParent() != null ? c.getParent().getId() : null);
    }
}
