package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductImage;

import java.util.UUID;

public record CatalogImageDto(UUID id, String url, String thumbnailUrl, String altText, boolean isPrimary, UUID variantId) {
    public static CatalogImageDto from(ProductImage i) {
        return new CatalogImageDto(i.getId(), i.getUrl(), i.getThumbnailUrl(), i.getAltText(), i.isPrimary(),
                i.getVariant() != null ? i.getVariant().getId() : null);
    }
}
