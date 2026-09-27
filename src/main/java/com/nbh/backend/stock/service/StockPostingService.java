package com.nbh.backend.stock.service;

import com.nbh.backend.common.event.LowStockThresholdCrossedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockBalance;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
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
 * sale, POS sale, goods receipt, adjustment, stock count — is expressed as a
 * {@link StockDocument} and posted through here.
 *
 * This is the direct structural fix for Moments Packaging's confirmed production
 * bug: {@code RisellerSyncService.syncStock()} performed a bare
 * {@code UPDATE product SET stock_count = :newCount}, an unconditional absolute
 * assignment with no ledger, no account and no concurrency protection. That is
 * precisely the class of write this service exists to make structurally
 * impossible — enforced by the ArchUnit rule in {@code SingleWritePathArchitectureTest}
 * that no class outside {@code com.nbh.backend.stock.service} may depend on
 * {@link StockBalanceRepository} or {@link StockReservationRepository} at all
 * (not just their mutating methods), so every reader goes through
 * {@link StockAvailabilityService} and every writer goes through here.
 *
 * <p>Branches/locations were removed 2026-09-24 (founder decision — see
 * HANDOFF.md). There is exactly one physical account, {@link StockAccount#SHOP};
 * the other three ({@code SUPPLIER}/{@code CUSTOMER}/{@code ADJUSTMENT}) are the
 * same virtual double-entry counterparts the old branch-scoped locations had.
 * This also makes two real bugs the old branch-fallback design had structurally
 * impossible rather than just unlikely: a reservation can now only ever be
 * fulfilled from SHOP (enforced below), and there is only one place "in stock"
 * can be computed, so no two callers can disagree about it.
 *
 * Locking protocol (adopted from ERPNext stock_reservation_entry.py
 * get_available_qty_to_reserve, L726/L749-753, and stock_ledger.py
 * make_sl_entries, L143-145):
 *   1. Collect every distinct (variantId, account) pair touched by the
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

    /** Phase 2 (2026-09-27): {@link #reverse}'s marker on a reversal document's referenceType. */
    public static final String REVERSAL_REFERENCE_TYPE = "STOCK_DOCUMENT_REVERSAL";

    /** Sales go through a return instead — see {@link #reverse}'s javadoc. */
    private static final Set<StockDocumentType> REVERSIBLE_TYPES = EnumSet.of(
            StockDocumentType.GOODS_RECEIPT, StockDocumentType.ADJUSTMENT,
            StockDocumentType.WRITE_OFF, StockDocumentType.STOCK_COUNT);

    private final StockDocumentRepository documentRepository;
    private final StockLedgerEntryRepository ledgerRepository;
    private final StockBalanceRepository balanceRepository;
    private final StockReservationRepository reservationRepository;
    private final StockDocumentNoGenerator documentNoGenerator;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public StockPostingService(
            StockDocumentRepository documentRepository,
            StockLedgerEntryRepository ledgerRepository,
            StockBalanceRepository balanceRepository,
            StockReservationRepository reservationRepository,
            StockDocumentNoGenerator documentNoGenerator,
            Clock clock,
            ApplicationEventPublisher eventPublisher
    ) {
        this.documentRepository = documentRepository;
        this.ledgerRepository = ledgerRepository;
        this.balanceRepository = balanceRepository;
        this.reservationRepository = reservationRepository;
        this.documentNoGenerator = documentNoGenerator;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Posts a DRAFT document: locks every (variant, account) pair it touches in
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

        // A reservation only ever holds SHOP stock (there is nowhere else to hold
        // it). Enforcing this here — not just trusting the caller — is what makes
        // "reserve at one place, dispatch from another" a structural impossibility
        // instead of a bug that only shows up when a fallback path is taken (this
        // is exactly the class of defect the old branch-fallback design had).
        //
        // Only .getId() is called on line.getReservation() here — it's a lazy proxy
        // (StockDocumentLine.reservation), and .getId() answers from the already-loaded
        // foreign-key column without initialising the proxy. Calling anything else on it
        // (e.g. the variant-match check this used to also do here) would force a real
        // SELECT of the StockReservation into this transaction's persistence context
        // BEFORE the locking loop below ever runs — and Hibernate then hands that same
        // stale managed instance back from lockById's own SELECT ... FOR UPDATE instead
        // of a fresh one (Phase 0 finding, 2026-09-27; see StockReservationService.terminate's
        // javadoc for the mechanism). The variant-match check moved below, after locking.
        for (StockDocumentLine line : document.getLines()) {
            StockReservation reservation = line.getReservation();
            if (reservation != null && line.getSourceAccount() != StockAccount.SHOP) {
                throw new IllegalArgumentException(
                        "Line fulfilling reservation " + reservation.getId() + " must source from SHOP, was "
                                + line.getSourceAccount());
            }
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

        // Step 1-2: every distinct (variant, account) pair this document touches,
        // in one deterministic order shared by every concurrent poster.
        Set<VariantAccountKey> pairs = new TreeSet<>();
        for (StockDocumentLine line : document.getLines()) {
            if (line.getSourceAccount() != null) {
                pairs.add(new VariantAccountKey(line.getVariant().getId(), line.getSourceAccount()));
            }
            if (line.getTargetAccount() != null) {
                pairs.add(new VariantAccountKey(line.getVariant().getId(), line.getTargetAccount()));
            }
        }

        // Step 3: lock every balance row up front, before any writes, in that order.
        Map<VariantAccountKey, StockBalance> lockedBalances = new HashMap<>();
        for (VariantAccountKey key : pairs) {
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

        // Deferred from the pre-lock validation above (see its comment): now that every
        // touched reservation is genuinely locked and freshly loaded, it's safe to read
        // .getVariant() off it.
        for (StockDocumentLine line : document.getLines()) {
            StockReservation reservation = line.getReservation() != null
                    ? lockedReservations.get(line.getReservation().getId())
                    : null;
            if (reservation != null && !reservation.getVariant().getId().equals(line.getVariant().getId())) {
                throw new IllegalArgumentException(
                        "Reservation " + reservation.getId() + " is for variant " + reservation.getVariant().getId()
                                + " but the line fulfilling it is for variant " + line.getVariant().getId());
            }
        }

        Instant postedAt = Instant.now(clock);

        // Phase 2 (2026-09-27): a reversal document (see reverse() below) sets referenceType
        // to REVERSAL_REFERENCE_TYPE and referenceId to the original document's id — resolved
        // once here into a (variant, account) -> original-entry lookup, so applyMovement can
        // tag each new entry with exactly which original entry it undoes. Empty for every
        // ordinary (non-reversal) document, which is the overwhelmingly common case.
        Map<VariantAccountKey, StockLedgerEntry> reversalSourceEntries = resolveReversalSourceEntries(document);

        // Step 4: apply every line. A line with both a source and a target produces
        // two ledger entries (one negative, one positive) — the double-entry
        // property that makes SUM(qty_change) per variant always zero across the
        // whole ledger.
        for (StockDocumentLine line : document.getLines()) {
            StockReservation fulfils = line.getReservation() != null
                    ? lockedReservations.get(line.getReservation().getId())
                    : null;
            if (line.getSourceAccount() != null) {
                VariantAccountKey key = new VariantAccountKey(line.getVariant().getId(), line.getSourceAccount());
                applyMovement(document, line, line.getSourceAccount(), line.getQty().negate(),
                        lockedBalances.get(key), postedAt, fulfils, reversalSourceEntries.get(key));
            }
            if (line.getTargetAccount() != null) {
                VariantAccountKey key = new VariantAccountKey(line.getVariant().getId(), line.getTargetAccount());
                // a reservation is only consumed on the outbound/source side
                applyMovement(document, line, line.getTargetAccount(), line.getQty(),
                        lockedBalances.get(key), postedAt, null, reversalSourceEntries.get(key));
            }
        }

        document.setStatus(StockDocument.StockDocumentStatus.POSTED);
        document.setPostedAt(postedAt);
        return documentRepository.save(document);
    }

    /**
     * Phase 2 (2026-09-27): undoes a previously posted document by posting its exact negation
     * — every line's source/target swapped, same quantities, each side valued at what the
     * ORIGINAL entry for that (variant, account) recorded (see applyMovement's reversal
     * branch), not a fresh current-average or a re-typed cost. Goes through the normal
     * {@link #post} — same locking, same negative-stock/reserved-floor guards, same
     * single-write-path — so a reversal that would take SHOP negative fails exactly the way
     * any other movement would (the design doc's "reject and point to a revaluation" guard is
     * this same existing check, not a separate one).
     *
     * <p>Only GRN/ADJUSTMENT/WRITE_OFF/STOCK_COUNT are reversible this way — a posted sale
     * must go through a customer return instead, since "undo the sale" and "the customer sent
     * it back" are different real-world events with different implications (a return can be
     * damaged-not-restocked; a plain reversal can't distinguish that).
     */
    @Transactional
    public StockDocument reverse(UUID documentId, String reason, UUID staffUserId) {
        StockDocument original = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("No such stock document: " + documentId));
        if (original.getStatus() != StockDocument.StockDocumentStatus.POSTED) {
            throw new UnprocessableException(
                    "Only a POSTED document can be reversed; " + original.getDocumentNo() + " is "
                            + original.getStatus());
        }
        if (!REVERSIBLE_TYPES.contains(original.getDocumentType())) {
            throw new UnprocessableException(
                    original.getDocumentType() + " documents can't be reversed directly — "
                            + "a sale must go through a customer return instead");
        }
        if (original.getCancelledByDocument() != null) {
            throw new UnprocessableException(
                    original.getDocumentNo() + " was already reversed by "
                            + original.getCancelledByDocument().getDocumentNo());
        }

        StockDocument reversal = new StockDocument();
        reversal.setDocumentType(StockDocumentType.ADJUSTMENT);
        reversal.setDocumentNo(documentNoGenerator.next(StockDocumentType.ADJUSTMENT));
        reversal.setPostedByUserId(staffUserId);
        reversal.setReferenceType(REVERSAL_REFERENCE_TYPE);
        reversal.setReferenceId(original.getId());
        reversal.setNotes(reason != null && !reason.isBlank() ? reason : ("Reversal of " + original.getDocumentNo()));

        int lineNo = 1;
        for (StockDocumentLine originalLine : original.getLines()) {
            StockDocumentLine reversalLine = new StockDocumentLine();
            reversalLine.setDocument(reversal);
            reversalLine.setLineNo(lineNo++);
            reversalLine.setVariant(originalLine.getVariant());
            reversalLine.setQty(originalLine.getQty());
            // Swapped: undoing a movement means running it backwards.
            reversalLine.setSourceAccount(originalLine.getTargetAccount());
            reversalLine.setTargetAccount(originalLine.getSourceAccount());
            reversal.getLines().add(reversalLine);
        }

        StockDocument posted = post(reversal);

        original.setCancelledByDocument(posted);
        documentRepository.save(original);

        return posted;
    }

    /**
     * Empty for every ordinary document (the overwhelmingly common case) — populated only when
     * {@code document} is a {@link #reverse} product, mapping each (variant, account) this
     * document touches back to the original ledger entry it's undoing, so {@link #applyMovement}
     * can value each side precisely and tag it as a reversal.
     */
    private Map<VariantAccountKey, StockLedgerEntry> resolveReversalSourceEntries(StockDocument document) {
        if (!REVERSAL_REFERENCE_TYPE.equals(document.getReferenceType()) || document.getReferenceId() == null) {
            return Map.of();
        }
        Map<VariantAccountKey, StockLedgerEntry> byKey = new HashMap<>();
        for (StockLedgerEntry original : ledgerRepository.findByDocumentIdOrderByIdAsc(document.getReferenceId())) {
            byKey.put(new VariantAccountKey(original.getVariant().getId(), original.getAccount()), original);
        }
        return byKey;
    }

    private StockBalance lockOrCreateBalance(VariantAccountKey key) {
        Optional<StockBalance> locked = balanceRepository.lockByVariantIdAndAccount(key.variantId(), key.account());
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
        balanceRepository.insertZeroIfAbsent(key.variantId(), key.account().name());
        return balanceRepository.lockByVariantIdAndAccount(key.variantId(), key.account()).orElseThrow();
    }

    private void applyMovement(
            StockDocument document,
            StockDocumentLine line,
            StockAccount account,
            BigDecimal signedQty,
            StockBalance balance,
            Instant postedAt,
            StockReservation fulfils,
            StockLedgerEntry reverses
    ) {
        BigDecimal onHandBefore = balance.getOnHandQty();
        BigDecimal newOnHand = onHandBefore.add(signedQty);

        // The negative-stock guard applies ONLY to the physical account (SHOP).
        // Virtual accounts (SUPPLIER/CUSTOMER/ADJUSTMENT) are the other side of
        // every double-entry movement by design — SUPPLIER goes negative as goods
        // are received from it, CUSTOMER goes negative as goods are sold to it,
        // etc. (architecture doc §4.2). Phase-1 default for SHOP: hard-blocked
        // (§8 Q5).
        boolean negativeAllowed = !account.isPhysical();
        if (newOnHand.signum() < 0 && !negativeAllowed) {
            throw new InsufficientStockException(
                    "Insufficient stock for variant " + line.getVariant().getSku()
                            + ": on hand " + balance.getOnHandQty() + ", requested change " + signedQty);
        }

        // Audit finding F3 (session 3): on_hand >= 0 is not enough — an
        // unreserved outbound movement at SHOP must also not dip below what OTHER
        // active holds still need, or a write-off/unreserved sale can silently
        // eat a paying customer's already-reserved unit with no error (the exact
        // oversell this whole ledger exists to prevent, reached through the
        // posting path instead of the reservation path). A line that legitimately
        // consumes ITS OWN reservation (fulfils != null) is exempt from that
        // floor for the qty it's consuming: reservedQty and onHandQty both drop
        // by the same amount below, so on_hand >= reserved is preserved
        // automatically — see the arithmetic note below.
        if (account.isPhysical() && signedQty.signum() < 0) {
            BigDecimal reservedFloor = fulfils != null
                    ? balance.getReservedQty().subtract(fulfils.getOutstandingQty())
                    : balance.getReservedQty();
            if (newOnHand.compareTo(reservedFloor) < 0) {
                throw new InsufficientStockException(
                        "Cannot post: variant " + line.getVariant().getSku()
                                + " would leave on_hand " + newOnHand + " below " + reservedFloor
                                + " still reserved by other holds (on hand " + balance.getOnHandQty()
                                + ", requested change " + signedQty + ")");
            }
        }

        BigDecimal newAvgCost = balance.getAvgCost();
        BigDecimal unitCostForEntry;
        BigDecimal stockValueDifference;

        if (reverses != null) {
            // Phase 2 (2026-09-27): a reversal is valued at exactly what the ORIGINAL entry
            // it undoes recorded, on both sides — not a fresh "current average" (which may
            // have drifted since) or a caller-supplied cost. This is what "the physical leg
            // is valued at the original leg's cost" (design doc §4.3) means in practice: the
            // undo is precise regardless of what's happened to the average since, and doesn't
            // depend on which direction either side now runs in.
            unitCostForEntry = reverses.getUnitCost();
            if (signedQty.signum() > 0) {
                BigDecimal existingValue = balance.getOnHandQty().multiply(balance.getAvgCost());
                BigDecimal incomingValue = signedQty.multiply(unitCostForEntry);
                newAvgCost = newOnHand.signum() == 0
                        ? BigDecimal.ZERO
                        : existingValue.add(incomingValue).divide(newOnHand, 4, RoundingMode.HALF_UP);
                stockValueDifference = incomingValue;
            } else {
                stockValueDifference = signedQty.multiply(unitCostForEntry);
            }
        } else if (signedQty.signum() > 0) {
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
        entry.setAccount(account);
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
        if (reverses != null) {
            entry.setReversal(true);
            entry.setReversesEntry(reverses);
        }
        StockLedgerEntry saved = ledgerRepository.save(entry);

        balance.setOnHandQty(newOnHand);
        balance.setAvgCost(newAvgCost);
        balance.setStockValue(newStockValue);
        balance.setLastLedgerEntryId(saved.getId());

        // Phase 0 fix (2026-09-27): SHOP is the only physical account, so its leg of a
        // movement carries the one cost figure a caller might need after the fact (e.g. a
        // sale's COGS) — captured here, under the lock, instead of a caller pre-reading an
        // avgCost snapshot before posting even runs (see StockDocumentLine.postedUnitCost).
        if (account == StockAccount.SHOP) {
            line.setPostedUnitCost(unitCostForEntry);
        }

        // §2: edge-triggered, not level-triggered — fires exactly once on the
        // movement that CROSSES the threshold, never on every subsequent sale of
        // an already-low SKU (that would be dozens of identical alerts per hour
        // during a promotion). Restricted to SHOP: reorderLevel exists to trigger
        // restocking real shelf stock, and a virtual account (SUPPLIER/CUSTOMER/
        // ADJUSTMENT) going more negative is not a "running low" event by that
        // definition — it's the designed other side of every double-entry movement.
        BigDecimal reorderLevel = line.getVariant().getReorderLevel();
        if (account.isPhysical() && reorderLevel != null
                && onHandBefore.compareTo(reorderLevel) >= 0
                && newOnHand.compareTo(reorderLevel) < 0) {
            eventPublisher.publishEvent(new LowStockThresholdCrossedEvent(
                    line.getVariant().getId(), line.getVariant().getSku(),
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

    private record VariantAccountKey(UUID variantId, StockAccount account) implements Comparable<VariantAccountKey> {
        @Override
        public int compareTo(VariantAccountKey other) {
            int cmp = this.variantId.compareTo(other.variantId);
            return cmp != 0 ? cmp : this.account.compareTo(other.account);
        }
    }
}
