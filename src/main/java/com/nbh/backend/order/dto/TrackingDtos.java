package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** Guest order lookup: what an order number alone reveals, and the emailed-code flow for everything else. */
public final class TrackingDtos {

    private TrackingDtos() {}

    /** All an order number on its own gives away: the status. No items, totals, names or delivery details. */
    public record OrderStatusDto(String orderNo, OrderStatus status) {}

    public record CodeRequest(@NotBlank @Email @Size(max = 255) String email) {}

    public record VerifyRequest(@NotBlank @Email @Size(max = 255) String email, @NotBlank @Size(max = 12) String code) {}

    /** {@code token} goes back in the {@code X-Track-Token} header; it is shown once and only its hash is stored. */
    public record SessionDto(String token, Instant expiresAt) {}

    /** A review left from the track page; the verified email session already proves who is reviewing. */
    public record TrackedReviewRequest(@jakarta.validation.constraints.NotNull java.util.UUID productId,
                                       @Min(1) @Max(5) int rating,
                                       @Size(max = 2000) String comment,
                                       @NotBlank @Size(max = 150) String reviewerName) {}

    /** One row of "the orders under this email" — enough to pick one; details are a second call. */
    public record TrackedOrderSummaryDto(String orderNo, OrderStatus status, Instant createdAt, long itemCount,
                                         BigDecimal grandTotal, FulfilmentType fulfilmentType) {}
}
