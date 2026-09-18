package com.nbh.backend.analytics.dto;

import com.nbh.backend.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The admin dashboard's headline numbers. {@code revenue}/{@code
 * ordersByStatus} only ever reflect {@code since} onward (null = all-time) —
 * see {@code AnalyticsController}'s javadoc for why revenue specifically
 * excludes orders that never actually got paid.
 */
public record AnalyticsSummaryDto(
        BigDecimal revenue,
        long totalOrders,
        Map<OrderStatus, Long> ordersByStatus,
        long lowStockCount
) {}
