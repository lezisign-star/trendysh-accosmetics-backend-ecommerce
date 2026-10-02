package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductReview;

import java.time.Instant;
import java.util.UUID;

/** What staff see when reading customer reviews: who said what about which product, from which order. */
public record AdminReviewDto(
        UUID id,
        UUID productId,
        String productName,
        String orderNo,
        int rating,
        String comment,
        String reviewerName,
        String reviewerPhone,
        ProductReview.ReviewStatus status,
        Instant createdAt
) {
    public static AdminReviewDto from(ProductReview r) {
        return new AdminReviewDto(r.getId(), r.getProduct().getId(), r.getProduct().getName(),
                r.getOrder().getOrderNo(), r.getRating(), r.getComment(), r.getReviewerName(),
                r.getReviewerPhone(), r.getStatus(), r.getCreatedAt());
    }
}
