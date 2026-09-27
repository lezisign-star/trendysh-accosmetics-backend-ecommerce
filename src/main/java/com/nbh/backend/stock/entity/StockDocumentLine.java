package com.nbh.backend.stock.entity;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "stock_document_line")
public class StockDocumentLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private StockDocument document;

    @Column(nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    /** At least one of source/target must be set — see the DB check constraint. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_account", length = 20)
    private StockAccount sourceAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_account", length = 20)
    private StockAccount targetAccount;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    @Column(precision = 14, scale = 2)
    private BigDecimal unitCost;

    @Column(precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(length = 60)
    private String batchNo;

    private LocalDate expiryDate;

    /**
     * When set, this outbound movement fulfils this hold — StockPostingService
     * decrements its reservedQty and closes it in the same transaction as the
     * ledger append (audit finding F3, session 3), and requires
     * {@code sourceAccount == StockAccount.SHOP} (a reservation only ever holds
     * SHOP stock). When null, the movement is unreserved and must not dip on_hand
     * below what OTHER active holds still need — see StockPostingService.applyMovement.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private StockReservation reservation;

    /**
     * Not persisted — populated by {@code StockPostingService.applyMovement} with whatever
     * unit cost SHOP's leg of this line actually posted at (the only physical account, so
     * the only one with a moving-average cost a caller cares about). Phase 0 fix
     * (2026-09-27): a caller that needs "what did this actually cost" (e.g.
     * {@code OrderFulfilmentService} snapshotting a sale's COGS) must read it from here,
     * AFTER {@code post()} returns — not from a separate pre-posting lookup, which can
     * read a cost that's already stale by the time posting actually locks the row and
     * commits.
     */
    @Transient
    private BigDecimal postedUnitCost;
}
