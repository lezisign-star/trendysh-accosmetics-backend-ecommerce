package com.nbh.backend.stock.entity;

import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.catalog.entity.ProductVariant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * APPEND-ONLY. Enforced at the DB level (trg_stock_ledger_entry_no_update/delete
 * in V3__stock_core.sql) — this is not a convention, it is a hard guarantee.
 * A correction is a reversal entry plus a new entry; nothing here is ever mutated.
 *
 * Deliberately NOT extending BaseEntity: a BIGSERIAL id (not UUID) keeps the
 * ledger's natural chronological/insertion order directly queryable, and there
 * is no updatedAt — this row never changes after insert.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_ledger_entry")
public class StockLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private StockLocation location;

    @Column(nullable = false)
    private Instant postedAt;

    /** Signed delta (ERPNext's actual_qty). */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyChange;

    /** Running balance at this location after this entry (ERPNext's qty_after_transaction). */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyAfter;

    @Column(precision = 14, scale = 2)
    private BigDecimal unitCost;

    // scale 4 — see StockBalance.avgCost for why (audit finding F11, session 3).
    @Column(precision = 14, scale = 4)
    private BigDecimal avgCostAfter;

    @Column(precision = 14, scale = 4)
    private BigDecimal stockValueAfter;

    @Column(precision = 14, scale = 4)
    private BigDecimal stockValueDifference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private StockDocument document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_line_id", nullable = false)
    private StockDocumentLine documentLine;

    @Column(nullable = false)
    private boolean isReversal = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reverses_entry_id")
    private StockLedgerEntry reversesEntry;

    private UUID createdByUserId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
