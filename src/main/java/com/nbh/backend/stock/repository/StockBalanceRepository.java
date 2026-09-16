package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.entity.StockBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockBalanceRepository extends JpaRepository<StockBalance, UUID> {

    Optional<StockBalance> findByVariantIdAndLocationId(UUID variantId, UUID locationId);

    List<StockBalance> findByVariantId(UUID variantId);

    List<StockBalance> findByLocationId(UUID locationId);

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
    @Query("select b from StockBalance b where b.variant.id = :variantId and b.location.id = :locationId")
    Optional<StockBalance> lockByVariantIdAndLocationId(UUID variantId, UUID locationId);

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
        INSERT INTO stock_balance (id, variant_id, location_id, on_hand_qty, reserved_qty, avg_cost, stock_value, created_at, updated_at)
        VALUES (gen_random_uuid(), :variantId, :locationId, 0, 0, 0, 0, now(), now())
        ON CONFLICT (variant_id, location_id) DO NOTHING
        """, nativeQuery = true)
    void insertZeroIfAbsent(@Param("variantId") UUID variantId, @Param("locationId") UUID locationId);
}
