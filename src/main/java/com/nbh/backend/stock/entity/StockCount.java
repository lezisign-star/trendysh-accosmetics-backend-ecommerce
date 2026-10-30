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
 * Phase 2 (2026-09-27): a physical stock count session — the one core inventory workflow this
 * system had no real path for. {@code blind}: when true, a counter without PERM_STOCK_ADJUST
 * can't see {@link StockCountLine#getSystemQtyAtStart()} through the read API (enforced at the
 * DTO layer, not here) — counting against a known target defeats the point of counting.
 *
 * <p>{@code version} matters here for the same reason it matters on {@link StockBalance}/
 * {@link StockReservation} (Phase 0 fix, 2026-09-27): applying is a real mutation two staff
 * could race on, and a stale-read-under-lock hazard is exactly the failure mode this whole
 * session has been closing everywhere else it was found.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_count")
public class StockCount extends BaseEntity {

    @Column(name = "count_no", nullable = false, unique = true, length = 40)
    private String countNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockCountStatus status = StockCountStatus.DRAFT;

    @Column(nullable = false)
    private boolean blind = false;

    @Column(length = 500)
    private String reason;

    private UUID assignedToUserId;

    @Column(nullable = false)
    private UUID createdByUserId;

    private UUID appliedByUserId;

    private Instant appliedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_document_id")
    private StockDocument appliedDocument;

    @Version
    @Column(nullable = false)
    private long version;

    /** The stock take it is part of (V85), and the top-level category it counts; null for a count made on its own. */
    @Column(name = "take_id")
    private UUID takeId;

    @Column(name = "category_id")
    private UUID categoryId;

    /** Who has counted in it, as names ("Mary, John"): a category can be counted by several people. */
    @Column(name = "counted_by_names", length = 500)
    private String countedByNames;

    @OneToMany(mappedBy = "stockCount", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockCountLine> lines = new ArrayList<>();
}
