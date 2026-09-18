package com.nbh.backend.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code orderNo} + {@code phone} together prove the reviewer actually placed
 * the order being reviewed — checkout is guest-only, so this stands in for a
 * customer login (see {@code ProductReview}'s javadoc).
 */
public record SubmitReviewRequest(
        @NotBlank String orderNo,
        @NotBlank String phone,
        @Min(1) @Max(5) int rating,
        String comment,
        @NotBlank String reviewerName
) {}
