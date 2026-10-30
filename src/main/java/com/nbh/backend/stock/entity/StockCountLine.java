package com.nbh.backend.stock.entity;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Phase 2 (2026-09-27): one variant's line within a {@link StockCount}. */
@Getter
@Setter
@Entity
@Table(name = "stock_count_line")
public class StockCountLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_count_id", nullable = false)
    private StockCount stockCount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    /** Frozen at line-creation time — see the class-level note on {@link StockCount}. */
    @Column(name = "system_qty_at_start", nullable = false, precision = 14, scale = 3)
    private BigDecimal systemQtyAtStart;

    @Column(precision = 14, scale = 3)
    private BigDecimal countedQty;

    private UUID countedByUserId;

    private Instant countedAt;

    @Column(length = 500)
    private String note;

    /** The counter chose to leave it as it was (V85): not changed when the count is applied. */
    @Column(nullable = false)
    private boolean skipped = false;

    @Transient
    public boolean isCounted() {
        return countedQty != null;
    }
}
