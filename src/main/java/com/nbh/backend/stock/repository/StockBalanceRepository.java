package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.dto.InventoryLineDto;
import com.nbh.backend.stock.dto.ValuationLineDto;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes here are restricted, in effect, by the ArchUnit rule in
 * {@code SingleWritePathArchitectureTest} ("no class outside
 * {@code com.nbh.backend.stock.service} may depend on this repository") — go
 * through {@link com.nbh.backend.stock.service.StockAvailabilityService} for
 * reads, {@link com.nbh.backend.stock.service.StockPostingService} for writes.
 */
public interface StockBalanceRepository extends JpaRepository<StockBalance, UUID> {

    Optional<StockBalance> findByVariantIdAndAccount(UUID variantId, StockAccount account);

    List<StockBalance> findByVariantId(UUID variantId);

    /**
     * Takes the row lock FIRST, per ERPNext's stock_reservation_entry.py
     * get_available_qty_to_reserve (L726, Postgres branch, L749-753): Postgres has
     * no gap locks, so if we locked an aggregate over stock_reservation instead,
     * an empty result set locks nothing and two concurrent "first reservation for
     * this SKU" calls can both succeed. Locking the balance row first — which
     * always exists once a variant has ever been received — serialises every
     * writer, including the very first one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from StockBalance b where b.variant.id = :variantId and b.account = :account")
    Optional<StockBalance> lockByVariantIdAndAccount(@Param("variantId") UUID variantId, @Param("account") StockAccount account);

    /**
     * Audit finding F5 (session 3): the original fix attempt caught
     * DataIntegrityViolationException around a plain save() and retried the
     * lock select in the SAME transaction — which cannot work on Postgres.
     * Once any statement in a Postgres transaction fails, that entire
     * transaction is aborted ("current transaction is aborted, commands
     * ignored until end of transaction block") and nothing else in it can
     * run, including the retry. Proven by ConcurrentPostingRaceTest's
     * first-receipt race, which failed with exactly that error.
     *
     * ON CONFLICT DO NOTHING never raises in the first place — the losing
     * concurrent inserter's statement simply affects zero rows and the
     * transaction continues normally, so the locking SELECT that follows it
     * always succeeds.
     */
    @Modifying
    @Query(value = """
        INSERT INTO stock_balance (id, variant_id, account, on_hand_qty, reserved_qty, avg_cost, stock_value, created_at, updated_at)
        VALUES (gen_random_uuid(), :variantId, CAST(:account AS varchar), 0, 0, 0, 0, now(), now())
        ON CONFLICT (variant_id, account) DO NOTHING
        """, nativeQuery = true)
    void insertZeroIfAbsent(@Param("variantId") UUID variantId, @Param("account") String account);

    /**
     * The staff-facing "browse stock levels" list the completeness tour found
     * missing — {@code GET /stock/balance} requires already knowing a variant
     * ID. SHOP only — a virtual account's balance (SUPPLIER, always negative;
     * CUSTOMER, always positive) is the double-entry mechanism, not an
     * inventory figure a human should browse. Same {@code CAST(:q AS string)}
     * null-parameter fix as {@code ProductRepository.searchPublished} for the
     * same reason.
     *
     * <p>Phase 1 fix (2026-09-27): this used to start FROM StockBalance, so a variant that
     * has never been received (no balance row exists at all) was invisible here — never
     * shown in the list, and (worse) never counted as "below reorder level" even though zero
     * stock is definitionally at or below any positive reorder level. Rewritten to start FROM
     * ProductVariant with an explicit LEFT JOIN to the (possibly absent) SHOP balance row,
     * COALESCEd to zero. See {@link #countBelowReorderLevel()} for the matching fix to the
     * analytics tile that reads the same underlying gap.
     */
    /**
     * 2026-10-05: variants of a DELETED product are no longer listed. Deleting is a soft delete that leaves the
     * variants (and their stock rows) in place for the order and movement history, so a product deleted as a
     * test used to stay in "Variants tracked" for good (398 of 541 rows on a copy of the dev data), while the
     * Products screen and the Forecast already hid it. Draft (unpublished) products stay: they are real and
     * staff stock them before they go live. {@code outOfStockOnly} keeps rows with nothing on hand (the same
     * test as the list's "Out of stock" pill), {@code productId} narrows to one product's variants (the
     * product card's quick edit) and a null one means every product.
     */
    @Query("""
            SELECT NEW com.nbh.backend.stock.dto.InventoryLineDto(
                v.id, p.id, v.sku, v.variantName, p.name,
                COALESCE(b.onHandQty, 0), COALESCE(b.reservedQty, 0),
                COALESCE(b.onHandQty, 0) - COALESCE(b.reservedQty, 0),
                v.reorderLevel,
                CASE WHEN v.reorderLevel IS NOT NULL AND COALESCE(b.onHandQty, 0) <= v.reorderLevel
                     THEN true ELSE false END,
                v.isKit)
            FROM ProductVariant v
            JOIN v.product p
            LEFT JOIN StockBalance b ON b.variant = v AND b.account = com.nbh.backend.stock.entity.StockAccount.SHOP
            WHERE v.deleted = false AND p.deleted = false
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (:belowReorderOnly = false
                   OR (v.reorderLevel IS NOT NULL AND COALESCE(b.onHandQty, 0) <= v.reorderLevel))
              AND (:outOfStockOnly = false OR COALESCE(b.onHandQty, 0) <= 0)
              AND (:productId IS NULL OR p.id = :productId)
              AND (:q IS NULL
                   OR LOWER(v.sku) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
            ORDER BY p.name ASC, v.sku ASC
            """)
    Page<InventoryLineDto> browseInventory(@Param("belowReorderOnly") boolean belowReorderOnly,
                                            @Param("outOfStockOnly") boolean outOfStockOnly,
                                            @Param("productId") UUID productId,
                                            @Param("q") String q,
                                            Pageable pageable);

    /**
     * The stock valuation report — {@code avgCost}/{@code stockValue} have been computed correctly on
     * every SHOP balance row since migration V30 (moving-average cost, {@code StockPostingService.applyMovement}),
     * nothing had ever read it back as a report. Same mock-data gating as the admin analytics dashboard
     * (`CustomerOrderRepository.totalRevenue` et al.) — a variant never received (no balance row) has
     * nothing to report, so this starts FROM StockBalance itself rather than ProductVariant (unlike
     * {@link #browseInventory}, which must show "never received" rows to flag them as below reorder level;
     * a valuation report has nothing to say about stock that was never received).
     */
    @Query("""
            SELECT NEW com.nbh.backend.stock.dto.ValuationLineDto(
                v.id, v.sku, v.variantName, p.name, b.onHandQty, b.avgCost, b.stockValue)
            FROM StockBalance b
            JOIN b.variant v
            JOIN v.product p
            WHERE b.account = com.nbh.backend.stock.entity.StockAccount.SHOP
              AND v.deleted = false
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (:q IS NULL
                   OR LOWER(v.sku) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
            ORDER BY p.name ASC, v.sku ASC
            """)
    Page<ValuationLineDto> valuationReport(@Param("q") String q, Pageable pageable);

    /** The valuation report's grand total — same filters as {@link #valuationReport}, minus paging. */
    @Query("""
            SELECT COALESCE(SUM(b.stockValue), 0)
            FROM StockBalance b
            JOIN b.variant v
            JOIN v.product p
            WHERE b.account = com.nbh.backend.stock.entity.StockAccount.SHOP
              AND v.deleted = false
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    java.math.BigDecimal totalStockValue();

    /**
     * Sellable quantity per variant (on-hand minus reserved, SHOP only) — backs
     * the public catalogue's inStock flag without exposing the numbers
     * themselves. Variants with no balance row at all simply don't appear in
     * the result (treated as zero by callers).
     */
    @Query("""
            SELECT b.variant.id AS variantId, (b.onHandQty - b.reservedQty) AS available
            FROM StockBalance b
            WHERE b.account = com.nbh.backend.stock.entity.StockAccount.SHOP AND b.variant.id IN :variantIds
            """)
    List<VariantAvailability> availabilityForVariants(@Param("variantIds") java.util.Collection<UUID> variantIds);

    interface VariantAvailability {
        UUID getVariantId();
        java.math.BigDecimal getAvailable();
    }

    /**
     * The admin analytics dashboard's "low stock" tile — same eligibility as
     * {@code browseInventory}'s filter, including the same Phase 1 fix (2026-09-27): starts
     * FROM ProductVariant now, so a never-received variant (zero stock, no balance row at
     * all) is correctly counted as below its reorder level instead of silently excluded.
     *
     * <p>Excludes mock products' variants unless demo data is still on — same gating/bug as
     * {@code CustomerOrderRepository.totalRevenue}.
     */
    @Query("""
            SELECT COUNT(v) FROM ProductVariant v
            JOIN v.product p
            LEFT JOIN StockBalance b ON b.variant = v AND b.account = com.nbh.backend.stock.entity.StockAccount.SHOP
            WHERE v.deleted = false AND p.deleted = false
              AND v.reorderLevel IS NOT NULL AND COALESCE(b.onHandQty, 0) <= v.reorderLevel
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    long countBelowReorderLevel();
}
