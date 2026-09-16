package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Category;

import java.util.UUID;

public record CategoryDto(UUID id, String slug, String name, UUID parentId) {
    public static CategoryDto from(Category c) {
        return new CategoryDto(c.getId(), c.getSlug(), c.getName(),
                c.getParent() != null ? c.getParent().getId() : null);
    }
}
