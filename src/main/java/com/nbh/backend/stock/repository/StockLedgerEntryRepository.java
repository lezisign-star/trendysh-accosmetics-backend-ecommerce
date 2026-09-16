package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.entity.StockLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * NOTE: save() and delete() methods here are restricted in effect only by
 * (a) the DB trigger rejecting UPDATE/DELETE on stock_ledger_entry, and
 * (b) the ArchUnit rule (SingleWritePathArchitectureTest) that only
 * StockPostingService may call a mutating method on this repository. Never
 * call those from anywhere else.
 */
public interface StockLedgerEntryRepository extends JpaRepository<StockLedgerEntry, Long> {

    @Query("""
        select e.qtyAfter from StockLedgerEntry e
        where e.variant.id = :variantId and e.location.id = :locationId
        order by e.postedAt desc, e.id desc
        limit 1
        """)
    Optional<BigDecimal> findLatestQtyAfter(UUID variantId, UUID locationId);

    /**
     * The actual source-of-truth reconciliation query for
     * {@link com.nbh.backend.stock.service.StockBalanceRebuildService} (audit
     * finding F7, session 3): recomputes on-hand per (variant, location) purely
     * by summing every ledger delta, rather than trusting the cached running
     * balance the same write path already produced.
     */
    @Query("select e.variant.id, e.location.id, sum(e.qtyChange) from StockLedgerEntry e group by e.variant.id, e.location.id")
    List<Object[]> sumQtyChangeByVariantAndLocation();

    /**
     * The double-entry invariant the architecture doc calls "a cheap, powerful
     * integrity check" (§4.2): every movement has a real side and a virtual
     * counterpart side, so summed across ALL locations, every variant's total
     * qty_change must be exactly zero.
     */
    @Query("select e.variant.id, sum(e.qtyChange) from StockLedgerEntry e group by e.variant.id")
    List<Object[]> sumQtyChangeByVariant();
}
