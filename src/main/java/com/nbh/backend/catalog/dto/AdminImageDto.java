package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductImage;

import java.time.Instant;
import java.util.UUID;

/**
 * A photo as staff see it: the same fields the shop gets, plus where it came from (so an AI-made photo can be
 * marked as one) and when it was added. {@code variantId} null means the photo is filed at product level, which on a
 * product with several options means it is not assigned to any option yet (the shop shows it nowhere).
 */
public record AdminImageDto(UUID id, String url, String thumbnailUrl, String altText, boolean isPrimary, UUID variantId,
                            ProductImage.ImageSource source, Instant createdAt) {
    public static AdminImageDto from(ProductImage i) {
        return new AdminImageDto(i.getId(), i.getUrl(), i.getThumbnailUrl(), i.getAltText(), i.isPrimary(),
                i.getVariant() != null ? i.getVariant().getId() : null, i.getSource(), i.getCreatedAt());
    }
}
