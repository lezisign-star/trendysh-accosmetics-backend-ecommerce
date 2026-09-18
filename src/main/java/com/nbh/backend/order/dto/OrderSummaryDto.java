package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The list-view projection for staff browsing orders — deliberately lighter
 * than {@link OrderDto}, which eagerly maps every line item. Matches the
 * F12 pattern ({@code ProductService.listProducts}): a summary for the list,
 * full detail only on a single-order fetch. {@code status} is typed as the
 * real enum (not a pre-stringified value) so it can be used directly in a
 * JPQL constructor-expression query without a type mismatch — Jackson still
 * serialises it as a plain string in the JSON response either way.
 */
public record OrderSummaryDto(
        UUID id,
        String orderNo,
        OrderStatus status,
        String customerName,
        String guestEmail,
        String guestPhone,
        long itemCount,
        BigDecimal grandTotal,
        Instant createdAt
) {}
