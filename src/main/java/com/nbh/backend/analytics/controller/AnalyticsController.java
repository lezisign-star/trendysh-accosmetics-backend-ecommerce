package com.nbh.backend.analytics.controller;

import com.nbh.backend.analytics.dto.AnalyticsSummaryDto;
import com.nbh.backend.analytics.dto.TopSellerDto;
import com.nbh.backend.catalog.repository.ProductSaleEventRepository;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The admin dashboard's numbers — the gap the completeness tour found: raw
 * event/order data existed, but nothing aggregated it. Deliberately its own
 * top-level package (not folded into {@code catalog} or {@code order}) since
 * it reads across both domains plus stock — an analytics endpoint naturally
 * cuts across the module boundaries the rest of this codebase otherwise
 * keeps clean.
 *
 * "Revenue" only ever counts orders whose payment actually succeeded (PAID
 * or later) — a PENDING_PAYMENT order (or one that was CANCELLED/
 * PAYMENT_FAILED/EXPIRED) must never inflate the figure a founder is reading
 * to judge how the business is doing, same principle {@code
 * ProductAnalyticsListener} already applies to per-product sale events.
 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AnalyticsController {

    private static final Set<OrderStatus> REVENUE_ELIGIBLE_STATUSES =
            EnumSet.of(OrderStatus.PAID, OrderStatus.PICKING, OrderStatus.READY,
                    OrderStatus.DISPATCHED, OrderStatus.DELIVERED);

    private final CustomerOrderRepository orderRepository;
    private final ProductSaleEventRepository saleEventRepository;
    private final StockBalanceRepository balanceRepository;

    public AnalyticsController(CustomerOrderRepository orderRepository,
                                ProductSaleEventRepository saleEventRepository,
                                StockBalanceRepository balanceRepository) {
        this.orderRepository = orderRepository;
        this.saleEventRepository = saleEventRepository;
        this.balanceRepository = balanceRepository;
    }

    @PreAuthorize("hasAuthority('PERM_ANALYTICS_VIEW')")
    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public AnalyticsSummaryDto summary(@RequestParam(required = false) Instant since) {
        Instant effectiveSince = since != null ? since : Instant.EPOCH;
        var revenue = orderRepository.totalRevenue(REVENUE_ELIGIBLE_STATUSES, effectiveSince);
        var statusCounts = orderRepository.countByStatus(effectiveSince);

        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        long total = 0;
        for (var sc : statusCounts) {
            byStatus.put(sc.getStatus(), sc.getCount());
            total += sc.getCount();
        }

        return new AnalyticsSummaryDto(revenue, total, byStatus, balanceRepository.countBelowReorderLevel());
    }

    @PreAuthorize("hasAuthority('PERM_ANALYTICS_VIEW')")
    @GetMapping("/top-products")
    @Transactional(readOnly = true)
    public List<TopSellerDto> topProducts(@RequestParam(required = false) Instant since,
                                           @RequestParam(defaultValue = "10") int limit) {
        Instant effectiveSince = since != null ? since : Instant.EPOCH;
        return saleEventRepository.topSellers(effectiveSince, PageRequest.of(0, Math.min(limit, 50))).stream()
                .map(s -> new TopSellerDto(s.getProductId(), s.getProductName(), s.getTotalQty()))
                .toList();
    }
}
