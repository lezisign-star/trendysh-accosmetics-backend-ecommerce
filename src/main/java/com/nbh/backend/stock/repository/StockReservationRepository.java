package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.entity.StockReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    /**
     * Locks a single reservation row by id — used by release/fulfill (after the
     * balance row is locked) so a stale in-memory read can never be acted on
     * concurrently by two callers (audit finding F4, session 3: an unlocked
     * findById let concurrent release+release or release+fulfill double-decrement
     * reservedQty).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservation r where r.id = :id")
    Optional<StockReservation> lockById(UUID id);

    /**
     * A scalar projection, not a full entity load — used by
     * {@code StockReservationService.terminate} to find which stock_balance row to lock
     * BEFORE touching the reservation itself. Loading the full entity here (even unlocked)
     * would put it in this transaction's persistence context, and the later
     * {@link #lockById} would then hand back that same stale managed instance instead of a
     * fresh row (Hibernate only re-validates a lock request against the DB when the entity
     * has changed identity or a @Version mismatches — it does not re-read columns into an
     * already-managed instance). A scalar query never populates the persistence context, so
     * it cannot poison the lock that follows it.
     */
    @Query("select r.variant.id from StockReservation r where r.id = :id")
    Optional<UUID> findVariantIdById(UUID id);

    /**
     * Locked in a deterministic order (id ASC) after the balance row lock, per
     * ERPNext's make_sl_entries (L143-145) deadlock-avoidance pattern: any two
     * transactions that need overlapping rows must acquire them in the same order.
     * No location dimension any more — a reservation is always against SHOP.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select r from StockReservation r
        where r.variant.id = :variantId
          and r.status = com.nbh.backend.stock.entity.StockReservation.ReservationStatus.ACTIVE
        order by r.id asc
        """)
    List<StockReservation> lockActiveByVariant(UUID variantId);

    List<StockReservation> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);

    /**
     * Phase 0 addition (2026-09-27): feeds {@code StockBalanceRebuildService}'s new
     * orphan-hold check — every ACTIVE reservation against a real order, so the caller can
     * batch-check whether that order has since reached a terminal state the hold should
     * have been released at.
     */
    List<StockReservation> findByStatusAndReferenceType(
            StockReservation.ReservationStatus status, String referenceType);

    /**
     * Phase 0 addition (2026-09-27): feeds {@code StockBalanceRebuildService}'s new
     * reserved-quantity check — {@code stock_balance.reserved_qty} is a cache of exactly this
     * sum (per variant, since a reservation only ever holds SHOP), and the two had no
     * independent cross-check before this. {@code getOutstandingQty()}'s formula
     * (qty - fulfilledQty - releasedQty) is inlined here since JPQL cannot call an entity's
     * plain Java method.
     */
    @Query("""
            SELECT r.variant.id AS variantId, SUM(r.qty - r.fulfilledQty - r.releasedQty) AS outstanding
            FROM StockReservation r
            WHERE r.status = com.nbh.backend.stock.entity.StockReservation.ReservationStatus.ACTIVE
            GROUP BY r.variant.id
            """)
    List<VariantOutstanding> sumOutstandingByVariant();

    interface VariantOutstanding {
        UUID getVariantId();
        java.math.BigDecimal getOutstanding();
    }

    /**
     * Feeds the expiry sweeper (audit finding F9, session 3): active
     * reservations whose hold has lapsed. Capped with a limit by the caller
     * via Pageable/first-N pattern isn't wired here — the sweeper runs
     * frequently enough (every minute) that the working set per run is small
     * in practice; revisit with a LIMIT if that stops being true at scale.
     */
    List<StockReservation> findByStatusAndExpiresAtBefore(
            StockReservation.ReservationStatus status, java.time.Instant cutoff);
}
