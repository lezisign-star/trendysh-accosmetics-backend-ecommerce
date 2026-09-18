package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductReview;

import java.time.Instant;
import java.util.UUID;

public record ProductReviewDto(
        UUID id,
        int rating,
        String comment,
        String reviewerName,
        ProductReview.ReviewStatus status,
        Instant createdAt
) {
    public static ProductReviewDto from(ProductReview r) {
        return new ProductReviewDto(r.getId(), r.getRating(), r.getComment(), r.getReviewerName(),
                r.getStatus(), r.getCreatedAt());
    }
}
