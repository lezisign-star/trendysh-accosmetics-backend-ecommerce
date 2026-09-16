package com.nbh.backend.stock.service;

import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.common.event.LowStockThresholdCrossedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.stock.entity.StockBalance;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockLedgerEntry;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.exception.InsufficientStockException;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockDocumentRepository;
import com.nbh.backend.stock.repository.StockLedgerEntryRepository;
import com.nbh.backend.stock.repository.StockReservationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * The ONLY class in this codebase permitted to write {@link StockLedgerEntry} or
 * {@link StockBalance} rows. Every stock-affecting event in the system — online
 * sale, POS sale, goods receipt, inter-branch transfer, adjustment, stock count —
 * is expressed as a {@link StockDocument} and posted through here.
 *
 * This is the direct structural fix for Moments Packaging's confirmed production
 * bug: {@code RisellerSyncService.syncStock()} performed a bare
 * {@code UPDATE product SET stock_count = :newCount}, an unconditional absolute
 * assignment with no ledger, no location, and no concurrency protection. That is
 * precisely the class of write this service exists to make structurally
 * impossible — see the ArchUnit rule in stock/architecture that enforces no other
 * class may call StockLedgerEntryRepository.save or StockBalanceRepository.save.
 *
 * Locking protocol (adopted from ERPNext stock_reservation_entry.py
 * get_available_qty_to_reserve, L726/L749-753, and stock_ledger.py
 * make_sl_entries, L143-145):
 *   1. Collect every distinct (variantId, locationId) pair touched by the
 *      document's lines.
 *   2. Sort the pairs into a deterministic order before locking any of them —
 *      this is what prevents two concurrent documents that touch an overlapping
 *      set of pairs from deadlocking by acquiring locks in opposite order.
 *   3. For each pair, in that order, SELECT ... FOR UPDATE the stock_balance row
 *      (creating it at zero first if this is the pair's first-ever movement).
 *      Locking the balance row — not an aggregate over the ledger — is what
 *      serialises even the very first write for a pair, which a Postgres
 *      gap-lock-free aggregate lock would not.
 *   4. Apply every line for that pair inside the same transaction: append a
 *      ledger entry, then update the now-locked balance row.
 */
@Service
public class StockPostingService {

    private final StockDocumentRepository documentRepository;
    private final StockLedgerEntryRepository ledgerRepository;
    private final StockBalanceRepository balanceRepository;
    private final StockReservationRepository reservationRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public StockPostingService(
            StockDocumentRepository documentRepository,
            StockLedgerEntryRepository ledgerRepository,
            StockBalanceRepository balanceRepository,
            StockReservationRepository reservationRepository,
            Clock clock,
            ApplicationEventPublisher eventPublisher
    ) {
        this.documentRepository = documentRepository;
        this.ledgerRepository = ledgerRepository;
        this.balanceRepository = balanceRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Posts a DRAFT document: locks every (variant, location) pair it touches in
     * a fixed order, appends one ledger entry per line-side, and updates the
     * derived balance. Idempotent on {@code document.idempotencyKey} — posting an
     * already-posted document (matched by idempotency key) is a no-op, which is
     * what lets a POS terminal or a payment webhook safely retry.
     *
     * @throws InsufficientStockException if a line would take on_hand_qty negative
     *         and negative stock is not explicitly allowed for this document type
     *         (Phase-1 default, per the architecture doc §8 Q5: hard-blocked).
     */
    @Transactional
    public StockDocument post(StockDocument document) {
        if (document.getIdempotencyKey() != null) {
            Optional<StockDocument> existing = documentRepository.findByIdempotencyKey(document.getIdempotencyKey());
            if (existing.isPresent() && existing.get().getStatus() == StockDocument.StockDocumentStatus.POSTED) {
                return existing.get();
            }
        }

        if (document.getStatus() != StockDocument.StockDocumentStatus.DRAFT) {
            throw new IllegalStateException("Only a DRAFT document may be posted; was " + document.getStatus());
        }
        if (document.getLines().isEmpty()) {
            throw new IllegalStateException("Cannot post a stock document with no lines");
        }

        // Persist the document and its lines FIRST (still DRAFT) so they have
        // real ids before any StockLedgerEntry references them — a ledger entry's
        // document/documentLine FKs are NOT NULL and Hibernate cannot resolve a
        // reference to a still-transient entity.
        //
        // Audit finding F5 (session 3): a concurrent replay of the same
        // idempotencyKey can still lose a race here and die on the UNIQUE
        // constraint. An earlier fix attempt caught the exception and tried to
        // re-query the winner IN THE SAME TRANSACTION — that cannot work on
        // Postgres, where any failed statement aborts the whole transaction and
        // makes every subsequent statement (including the recovery query) fail
        // with "current transaction is aborted" (proven by
        // ConcurrentPostingRaceTest's first-receipt race hitting exactly that
        // error via the analogous balance-creation path, fixed below with
        // ON CONFLICT DO NOTHING instead). A document-level equivalent of that
        // fix would need a fresh transaction (TransactionTemplate with
        // REQUIRES_NEW) to run the recovery query — not yet built, since no
        // caller sets idempotencyKey yet (that lands with POS/Phase 4). Until
        // then, a genuine collision here propagates and GlobalExceptionHandler
        // maps it to 409 — a caller-visible "please retry", not a silent 500.
        document = documentRepository.saveAndFlush(document);

        // Step 1-2: every distinct (variant, location) pair this document touches,
        // in one deterministic order shared by every concurrent poster.
        Set<VariantLocationKey> pairs = new TreeSet<>();
        for (StockDocumentLine line : document.getLines()) {
            if (line.getSourceLocation() != null) {
                pairs.add(new VariantLocationKey(line.getVariant().getId(), line.getSourceLocation().getId()));
            }
            if (line.getTargetLocation() != null) {
                pairs.add(new VariantLocationKey(line.getVariant().getId(), line.getTargetLocation().getId()));
            }
        }

        // Step 3: lock every balance row up front, before any writes, in that order.
        Map<VariantLocationKey, StockBalance> lockedBalances = new HashMap<>();
        for (VariantLocationKey key : pairs) {
            lockedBalances.put(key, lockOrCreateBalance(key));
        }

        // Step 3b: lock every reservation this document fulfils, AFTER all balance
        // rows and in id order — same deadlock-avoidance rule as the balance locks
        // (ERPNext make_sl_entries L143-145). This is what makes it safe for
        // StockReservationService.release/fulfill and this method to race on the
        // same hold: whichever gets here first wins, the other sees a non-ACTIVE
        // status once it acquires the lock and fails cleanly instead of double-
        // consuming it (audit finding F4, applied symmetrically here for F3).
        List<UUID> reservationIds = document.getLines().stream()
                .map(StockDocumentLine::getReservation)
                .filter(Objects::nonNull)
                .map(StockReservation::getId)
                .distinct()
                .sorted()
                .toList();
        Map<UUID, StockReservation> lockedReservations = new HashMap<>();
        for (UUID id : reservationIds) {
            StockReservation reservation = reservationRepository.lockById(id)
                    // §4 C7: was IllegalArgumentException (400) — a referenced entity
                    // missing is a 404, not "the client sent a malformed request".
                    .orElseThrow(() -> new NotFoundException("No such reservation: " + id));
            if (reservation.getStatus() != StockReservation.ReservationStatus.ACTIVE) {
                throw new IllegalStateException(
                        "Reservation " + id + " is " + reservation.getStatus() + ", not ACTIVE — cannot fulfil it");
            }
            lockedReservations.put(id, reservation);
        }

        Instant postedAt = Instant.now(clock);

        // Step 4: apply every line. A transfer line has both a source and a
        // target, producing two ledger entries (one negative, one positive) —
        // the double-entry property that makes SUM(qty_change) per variant
        // always zero across the whole ledger.
        for (StockDocumentLine line : document.getLines()) {
            StockReservation fulfils = line.getReservation() != null
                    ? lockedReservations.get(line.getReservation().getId())
                    : null;
            if (line.getSourceLocation() != null) {
                applyMovement(document, line, line.getSourceLocation(), line.getQty().negate(),
                        lockedBalances.get(new VariantLocationKey(line.getVariant().getId(), line.getSourceLocation().getId())),
                        postedAt, fulfils);
            }
            if (line.getTargetLocation() != null) {
                applyMovement(document, line, line.getTargetLocation(), line.getQty(),
                        lockedBalances.get(new VariantLocationKey(line.getVariant().getId(), line.getTargetLocation().getId())),
                        postedAt, null); // a reservation is only consumed on the outbound/source side
            }
        }

        document.setStatus(StockDocument.StockDocumentStatus.POSTED);
        document.setPostedAt(postedAt);
        return documentRepository.save(document);
    }

    private StockBalance lockOrCreateBalance(VariantLocationKey key) {
        Optional<StockBalance> locked = balanceRepository.lockByVariantIdAndLocationId(key.variantId(), key.locationId());
        if (locked.isPresent()) {
            return locked.get();
        }
        // First-ever movement for this pair. Audit finding F5 (session 3): the
        // previous version here did save() then, on a UNIQUE-constraint failure,
        // caught the exception and retried the lock select IN THE SAME
        // TRANSACTION — which cannot work on Postgres. Once any statement in a
        // Postgres transaction fails, the WHOLE transaction is aborted and every
        // subsequent statement (the "retry") fails too, with "current
        // transaction is aborted, commands ignored until end of transaction
        // block". Proven directly by ConcurrentPostingRaceTest's first-receipt
        // race.
        //
        // ON CONFLICT DO NOTHING never raises in the first place: the losing
        // concurrent inserter's statement simply affects zero rows and the
        // transaction carries on normally, so the locking SELECT immediately
        // after always finds a row — its own insert if it won, the winner's row
        // if it lost — with no exception on either path.
        balanceRepository.insertZeroIfAbsent(key.variantId(), key.locationId());
        return balanceRepository.lockByVariantIdAndLocationId(key.variantId(), key.locationId()).orElseThrow();
    }

    private void applyMovement(
            StockDocument document,
            StockDocumentLine line,
            StockLocation location,
            BigDecimal signedQty,
            StockBalance balance,
            Instant postedAt,
            StockReservation fulfils
    ) {
        BigDecimal onHandBefore = balance.getOnHandQty();
        BigDecimal newOnHand = onHandBefore.add(signedQty);

        // The negative-stock guard applies ONLY to internal (physical) locations.
        // Virtual locations (SUPPLIER/CUSTOMER/ADJUSTMENT/IN_TRANSIT) are the
        // other side of every double-entry movement by design — SUPPLIER goes
        // negative as goods are received from it, CUSTOMER goes negative as
        // goods are sold to it, etc. (architecture doc §4.2). Phase-1 default
        // for physical locations: hard-blocked (§8 Q5).
        boolean negativeAllowed = !location.isInternal();
        if (newOnHand.signum() < 0 && !negativeAllowed) {
            throw new InsufficientStockException(
                    "Insufficient stock for variant " + line.getVariant().getSku()
                            + " at location " + location.getCode()
                            + ": on hand " + balance.getOnHandQty() + ", requested change " + signedQty);
        }

        // Audit finding F3 (session 3): on_hand >= 0 is not enough — an
        // unreserved outbound movement at an internal location must also not dip
        // below what OTHER active holds still need, or a write-off/unreserved
        // sale can silently eat a paying customer's already-reserved unit with no
        // error (the exact oversell this whole ledger exists to prevent, reached
        // through the posting path instead of the reservation path). A line that
        // legitimately consumes ITS OWN reservation (fulfils != null) is exempt
        // from that floor for the qty it's consuming: reservedQty and onHandQty
        // both drop by the same amount below, so on_hand >= reserved is preserved
        // automatically — see the arithmetic note below.
        if (location.isInternal() && signedQty.signum() < 0) {
            BigDecimal reservedFloor = fulfils != null
                    ? balance.getReservedQty().subtract(fulfils.getOutstandingQty())
                    : balance.getReservedQty();
            if (newOnHand.compareTo(reservedFloor) < 0) {
                throw new InsufficientStockException(
                        "Cannot post: variant " + line.getVariant().getSku() + " at location " + location.getCode()
                                + " would leave on_hand " + newOnHand + " below " + reservedFloor
                                + " still reserved by other holds (on hand " + balance.getOnHandQty()
                                + ", requested change " + signedQty + ")");
            }
        }

        BigDecimal newAvgCost = balance.getAvgCost();
        BigDecimal unitCostForEntry;
        BigDecimal stockValueDifference;

        if (signedQty.signum() > 0) {
            // Inbound: moving-average cost update. unit_cost is required on inbound lines.
            BigDecimal incomingCost = line.getUnitCost() != null ? line.getUnitCost() : balance.getAvgCost();
            BigDecimal existingValue = balance.getOnHandQty().multiply(balance.getAvgCost());
            BigDecimal incomingValue = signedQty.multiply(incomingCost);
            newAvgCost = newOnHand.signum() == 0
                    ? BigDecimal.ZERO
                    : existingValue.add(incomingValue).divide(newOnHand, 4, RoundingMode.HALF_UP);
            unitCostForEntry = incomingCost;
            stockValueDifference = incomingValue;
        } else {
            // Outbound: average cost is unchanged; the outbound entry's unit_cost is
            // the CURRENT average, captured at the moment of sale — this is what
            // makes historical margin reporting correct even after prices move later.
            unitCostForEntry = balance.getAvgCost();
            stockValueDifference = signedQty.multiply(balance.getAvgCost());
        }

        BigDecimal newStockValue = newOnHand.multiply(newAvgCost);

        StockLedgerEntry entry = new StockLedgerEntry();
        entry.setVariant(line.getVariant());
        entry.setLocation(location);
        entry.setPostedAt(postedAt);
        entry.setQtyChange(signedQty);
        entry.setQtyAfter(newOnHand);
        entry.setUnitCost(unitCostForEntry);
        entry.setAvgCostAfter(newAvgCost);
        entry.setStockValueAfter(newStockValue);
        entry.setStockValueDifference(stockValueDifference);
        entry.setDocument(document);
        entry.setDocumentLine(line);
        entry.setCreatedAt(postedAt);
        StockLedgerEntry saved = ledgerRepository.save(entry);

        balance.setOnHandQty(newOnHand);
        balance.setAvgCost(newAvgCost);
        balance.setStockValue(newStockValue);
        balance.setLastLedgerEntryId(saved.getId());

        // §2: edge-triggered, not level-triggered — fires exactly once on the
        // movement that CROSSES the threshold, never on every subsequent sale of
        // an already-low SKU (that would be dozens of identical alerts per hour
        // during a promotion). Restricted to internal locations: reorderLevel
        // exists to trigger restocking real shelf stock, and a virtual location
        // (SUPPLIER/CUSTOMER/ADJUSTMENT/IN_TRANSIT) going more negative is not a
        // "running low" event by that definition — it's the designed other side
        // of every double-entry movement.
        BigDecimal reorderLevel = line.getVariant().getReorderLevel();
        if (location.isInternal() && reorderLevel != null
                && onHandBefore.compareTo(reorderLevel) >= 0
                && newOnHand.compareTo(reorderLevel) < 0) {
            eventPublisher.publishEvent(new LowStockThresholdCrossedEvent(
                    line.getVariant().getId(), line.getVariant().getSku(), location.getId(),
                    onHandBefore, newOnHand, reorderLevel));
        }

        // Close the hold in the SAME transaction as the ledger append (audit
        // finding F3, session 3) — this also removes the crash-between-post-and-
        // separately-called-fulfill window the old two-call design had.
        if (fulfils != null) {
            BigDecimal outstanding = fulfils.getOutstandingQty();
            fulfils.setFulfilledQty(fulfils.getFulfilledQty().add(outstanding));
            fulfils.setStatus(StockReservation.ReservationStatus.FULFILLED);
            balance.setReservedQty(balance.getReservedQty().subtract(outstanding));
            reservationRepository.save(fulfils);
        }

        balanceRepository.save(balance);
    }

    private record VariantLocationKey(UUID variantId, UUID locationId) implements Comparable<VariantLocationKey> {
        @Override
        public int compareTo(VariantLocationKey other) {
            int cmp = this.variantId.compareTo(other.variantId);
            return cmp != 0 ? cmp : this.locationId.compareTo(other.locationId);
        }
    }
}
