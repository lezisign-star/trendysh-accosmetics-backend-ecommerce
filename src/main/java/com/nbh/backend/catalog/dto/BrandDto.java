package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.Brand;

import java.util.UUID;

public record BrandDto(UUID id, String slug, String name) {
    public static BrandDto from(Brand b) {
        return new BrandDto(b.getId(), b.getSlug(), b.getName());
    }
}
