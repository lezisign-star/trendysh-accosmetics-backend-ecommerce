package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.entity.StockLedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * NOTE: save() and delete() methods here are restricted in effect only by
 * (a) the DB trigger rejecting UPDATE/DELETE on stock_ledger_entry, and
 * (b) the ArchUnit rule (SingleWritePathArchitectureTest) that only
 * StockPostingService may call a mutating method on this repository. Never
 * call those from anywhere else.
 */
public interface StockLedgerEntryRepository extends JpaRepository<StockLedgerEntry, Long> {

    /**
     * The actual source-of-truth reconciliation query for
     * {@link com.nbh.backend.stock.service.StockBalanceRebuildService} (audit
     * finding F7, session 3): recomputes on-hand per (variant, account) purely
     * by summing every ledger delta, rather than trusting the cached running
     * balance the same write path already produced.
     */
    @Query("select e.variant.id, e.account, sum(e.qtyChange) from StockLedgerEntry e group by e.variant.id, e.account")
    List<Object[]> sumQtyChangeByVariantAndAccount();

    /**
     * The double-entry invariant the architecture doc calls "a cheap, powerful
     * integrity check" (§4.2): every movement has a real side and a virtual
     * counterpart side, so summed across ALL accounts, every variant's total
     * qty_change must be exactly zero.
     */
    @Query("select e.variant.id, sum(e.qtyChange) from StockLedgerEntry e group by e.variant.id")
    List<Object[]> sumQtyChangeByVariant();

    /**
     * Phase 1 (2026-09-27): the movement-history view — feeds {@code StockMovementQueryService},
     * the first reader the ledger has ever had. {@code from}/{@code to} are always real, bindable
     * values (never null) — the caller resolves an absent filter to {@code Instant.EPOCH}/
     * {@code Instant.now()} before calling this, the same documented fix
     * {@code CustomerOrderRepository.totalRevenue} already uses for the identical Postgres
     * parameter-type-inference failure on a null {@code Instant} bound into a {@code >=}/{@code <=}
     * comparison. {@code variantId}/{@code documentType} stay nullable — plain equality against a
     * non-string parameter doesn't hit that bug.
     */
    @Query(value = """
            SELECT e FROM StockLedgerEntry e
            JOIN FETCH e.variant v
            JOIN FETCH v.product
            JOIN FETCH e.document d
            WHERE (:variantId IS NULL OR v.id = :variantId)
              AND (:documentType IS NULL OR d.documentType = :documentType)
              AND e.postedAt >= :from AND e.postedAt <= :to
            ORDER BY e.postedAt DESC, e.id DESC
            """,
            countQuery = """
            SELECT COUNT(e) FROM StockLedgerEntry e
            JOIN e.variant v
            JOIN e.document d
            WHERE (:variantId IS NULL OR v.id = :variantId)
              AND (:documentType IS NULL OR d.documentType = :documentType)
              AND e.postedAt >= :from AND e.postedAt <= :to
            """)
    Page<StockLedgerEntry> search(@Param("variantId") UUID variantId,
                                   @Param("documentType") StockDocumentType documentType,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to,
                                   Pageable pageable);

    /** Phase 1 (2026-09-27): a document's own ledger entries, for {@code GET /stock/documents/{id}}. */
    @Query("""
            SELECT e FROM StockLedgerEntry e
            JOIN FETCH e.variant v
            JOIN FETCH v.product
            WHERE e.document.id = :documentId
            ORDER BY e.id ASC
            """)
    List<StockLedgerEntry> findByDocumentIdOrderByIdAsc(@Param("documentId") UUID documentId);
}
