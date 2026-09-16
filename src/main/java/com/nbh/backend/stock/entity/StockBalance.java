package com.nbh.backend.stock.entity;

import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The derived, cached per-(variant, location) balance — ERPNext's Bin / Odoo's
 * quant. Always reconstructible from {@link StockLedgerEntry} alone; that
 * reconciliation producing byte-identical results is this subsystem's
 * acceptance test (see StockRebuildService, Phase 1 gate).
 *
 * availableQty is deliberately NOT a column — it is always
 * onHandQty - reservedQty, computed on read, never stored and never stale.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_balance", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"variant_id", "location_id"})
})
public class StockBalance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private StockLocation location;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal onHandQty = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal reservedQty = BigDecimal.ZERO;

    // scale 4, not 2 — matches StockPostingService's moving-average computation
    // exactly (audit finding F11, session 3); a 2dp column silently truncated
    // the 4dp in-memory value, so a from-ledger rebuild could never reproduce
    // stock_value byte-identically.
    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal avgCost = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal stockValue = BigDecimal.ZERO;

    private Long lastLedgerEntryId;

    @Transient
    public BigDecimal getAvailableQty() {
        return onHandQty.subtract(reservedQty);
    }
}
