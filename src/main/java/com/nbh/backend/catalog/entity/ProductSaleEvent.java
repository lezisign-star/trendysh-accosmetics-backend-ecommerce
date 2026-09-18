package com.nbh.backend.catalog.entity;

import com.nbh.backend.order.entity.CustomerOrder;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per order line, written when payment is CONFIRMED — not when the
 * order is merely placed, since an abandoned/unpaid order must never count
 * towards "best sellers". Deliberately not extending {@link com.nbh.backend.common.entity.BaseEntity}
 * — an append-only fact, never updated, same posture as {@link com.nbh.backend.order.entity.CustomerOrderItem}.
 */
@Getter
@Setter
@Entity
@Table(name = "product_sale_event")
public class ProductSaleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
