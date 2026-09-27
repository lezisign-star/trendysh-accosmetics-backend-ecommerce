package com.nbh.backend.stock.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The "voucher" (ERPNext Stock Entry / Odoo picking). One header, N lines.
 * {@link com.nbh.backend.stock.service.StockPostingService} is the ONLY class
 * permitted to move a document DRAFT -&gt; POSTED and write ledger/balance rows.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_document")
public class StockDocument extends BaseEntity {

    @Column(nullable = false, unique = true, length = 40)
    private String documentNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockDocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StockDocumentStatus status = StockDocumentStatus.DRAFT;

    private Instant postedAt;

    private UUID postedByUserId;

    @Column(length = 40)
    private String referenceType;

    private UUID referenceId;

    @Column(length = 100)
    private String idempotencyKey;

    @Column(length = 1000)
    private String notes;

    /** Phase 2 (2026-09-27): only WRITE_OFF documents set this — shrinkage analytics needs "why", not just "how much". */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 20)
    private WriteOffReason reasonCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_document_id")
    private StockDocument cancelledByDocument;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockDocumentLine> lines = new ArrayList<>();

    public enum StockDocumentStatus { DRAFT, POSTED, CANCELLED }
}
