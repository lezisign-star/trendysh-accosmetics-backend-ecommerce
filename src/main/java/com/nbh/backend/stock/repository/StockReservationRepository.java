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
     * Locked in a deterministic order (id ASC) after the balance row lock, per
     * ERPNext's make_sl_entries (L143-145) deadlock-avoidance pattern: any two
     * transactions that need overlapping rows must acquire them in the same order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select r from StockReservation r
        where r.variant.id = :variantId and r.location.id = :locationId
          and r.status = com.nbh.backend.stock.entity.StockReservation.ReservationStatus.ACTIVE
        order by r.id asc
        """)
    List<StockReservation> lockActiveByVariantAndLocation(UUID variantId, UUID locationId);

    List<StockReservation> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);

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
