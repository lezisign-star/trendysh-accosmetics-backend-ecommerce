package com.nbh.backend.order.entity;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.stock.entity.StockReservation;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Deliberately NOT extending BaseEntity — no updatedAt: an order line is
 * immutable once the order is placed (a change means cancelling and
 * re-ordering, not editing history), mirroring the append-only posture used
 * elsewhere in this codebase for anything that becomes a record of what
 * actually happened.
 */
@Getter
@Setter
@Entity
@Table(name = "customer_order_item")
public class CustomerOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal vatRate;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    /** Filled in at fulfilment time from the stock ledger's outbound entry — the real margin figure. */
    // Phase 0 fix (2026-09-27), D7: was scale 2, silently truncating the 4dp moving-average
    // cost this is sourced from (see StockLedgerEntry.unitCost's comment).
    @Column(name = "unit_cost_at_sale", precision = 14, scale = 4)
    private BigDecimal unitCostAtSale;

    /** The hold this line is backed by — consumed (closed) when StockPostingService fulfils it at dispatch. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private StockReservation reservation;
}
