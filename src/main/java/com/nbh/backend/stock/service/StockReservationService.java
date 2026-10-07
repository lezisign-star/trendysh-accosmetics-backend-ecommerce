package com.nbh.backend.stock.service;

import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockBalance;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.stock.exception.InsufficientAvailabilityException;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockReservationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The anti-oversell mechanism. An online checkout and a POS sale both call
 * {@link #reserve} for the same variant — this is the exact omnichannel hazard
 * Moments Packaging never solved (its Product.reservedCount column exists but
 * is never written to anywhere in the codebase).
 *
 * <p>A reservation always holds SHOP stock — there is nowhere else to hold it
 * since branches/locations were removed (2026-09-24, founder decision; see
 * HANDOFF.md).
 *
 * Adopted from ERPNext's stock_reservation_entry.py get_available_qty_to_reserve
 * (L726) and its Postgres-specific locking note (L749-753): lock the
 * stock_balance row FIRST (it always exists once a variant has been received —
 * see StockPostingService — so this alone serialises the very first reservation
 * too, which an aggregate lock over zero reservation rows would not), THEN lock
 * active reservation rows in a deterministic order (id ASC) before computing
 * availability. ERPNext runs two separate pending-quantity mechanisms for sales
 * orders vs. POS; this service deliberately unifies both into one table with a
 * `source` discriminator so a third channel never gets forgotten.
 */
@Service
public class StockReservationService {

    private final StockBalanceRepository balanceRepository;
    private final StockReservationRepository reservationRepository;
    private final Clock clock;

    public StockReservationService(StockBalanceRepository balanceRepository,
                                    StockReservationRepository reservationRepository,
                                    Clock clock) {
        this.balanceRepository = balanceRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    /**
     * Attempts to reserve {@code qty} of {@code variant} at SHOP. Throws
     * {@link InsufficientAvailabilityException} if the requested quantity exceeds
     * on-hand minus every other currently-active reservation.
     */
    @Transactional
    public StockReservation reserve(
            ProductVariant variant,
            BigDecimal qty,
            StockReservation.ReservationSource source,
            String referenceType,
            UUID referenceId,
            Instant expiresAt
    ) {
        // 1. Balance row lock first — see class javadoc.
        StockBalance balance = balanceRepository
                .lockByVariantIdAndAccount(variant.getId(), StockAccount.SHOP)
                .orElseThrow(() -> new InsufficientAvailabilityException(
                        "No stock has ever been received for " + variant.getSku()));

        // 2. Active reservation rows, locked in a deterministic order.
        List<StockReservation> activeReservations =
                reservationRepository.lockActiveByVariant(variant.getId());

        Instant now = Instant.now(clock);
        BigDecimal reservedOutstanding = activeReservations.stream()
                .filter(r -> r.getExpiresAt() == null || r.getExpiresAt().isAfter(now))
                .map(StockReservation::getOutstandingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal available = balance.getOnHandQty().subtract(reservedOutstanding);

        if (available.compareTo(qty) < 0) {
            throw new InsufficientAvailabilityException(
                    "Requested " + qty + " of " + variant.getSku()
                            + " but only " + available + " is available");
        }

        StockReservation reservation = new StockReservation();
        reservation.setVariant(variant);
        reservation.setQty(qty);
        reservation.setSource(source);
        reservation.setReferenceType(referenceType);
        reservation.setReferenceId(referenceId);
        reservation.setStatus(StockReservation.ReservationStatus.ACTIVE);
        reservation.setExpiresAt(expiresAt);

        balance.setReservedQty(balance.getReservedQty().add(qty));
        balanceRepository.save(balance);

        return reservationRepository.save(reservation);
    }

    /**
     * True if any of these reservations is still ACTIVE. Lets the order package ask whether an
     * unpaid order still holds stock without reading stock tables itself (the read-path rule).
     */
    @Transactional(readOnly = true)
    public boolean anyActive(java.util.Collection<UUID> reservationIds) {
        return !reservationIds.isEmpty()
                && reservationRepository.existsByIdInAndStatus(reservationIds, StockReservation.ReservationStatus.ACTIVE);
    }

    /** Phase 1 (2026-09-27): the staff-facing "Holds" list — see the repository query's javadoc. */
    @Transactional(readOnly = true)
    public Page<StockReservation> browse(StockReservation.ReservationStatus status, Pageable pageable) {
        return reservationRepository.browse(status, pageable);
    }

    /** Releases (cancels) an active reservation — cart expiry, payment failure. */
    @Transactional
    public void release(UUID reservationId) {
        terminate(reservationId, StockReservation.ReservationStatus.RELEASED);
    }

    /**
     * Marks a reservation fulfilled directly — e.g. an admin manually closing a
     * stale hold. The normal path is {@link StockPostingService#post} consuming
     * a reservation as part of posting the actual sale document, in the same
     * transaction as the ledger append; this method exists for the cases where
     * no stock document applies.
     */
    @Transactional
    public void fulfill(UUID reservationId) {
        terminate(reservationId, StockReservation.ReservationStatus.FULFILLED);
    }

    /** Called only by {@link StockReservationExpirySweeper} for a hold whose TTL has passed. */
    @Transactional
    public void expire(UUID reservationId) {
        terminate(reservationId, StockReservation.ReservationStatus.EXPIRED);
    }

    /**
     * Pushes an ACTIVE, time-limited hold's expiry out to {@code newExpiresAt}
     * (never earlier). Used when a customer says they've paid but staff haven't
     * yet verified it: the items must not be released from under a real payment.
     * Locks the reservation row like every other mutation, so it cannot race the
     * expiry sweeper. A hold that already lapsed is left alone and reported
     * false: the caller must not pretend it is still reserved.
     */
    @Transactional
    public boolean extendHold(UUID reservationId, Instant newExpiresAt) {
        StockReservation reservation = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new NotFoundException("No such reservation: " + reservationId));
        if (reservation.getStatus() != StockReservation.ReservationStatus.ACTIVE) {
            return false;
        }
        Instant current = reservation.getExpiresAt();
        if (current != null && current.isBefore(newExpiresAt)) {
            reservation.setExpiresAt(newExpiresAt);
            reservationRepository.save(reservation);
        }
        return true;
    }

    /**
     * Checkout lifecycle step (architecture doc §4.5's reservation table):
     * "Payment confirmed -> ONLINE_ORDER, expires_at = NULL". A cart hold is
     * time-limited (15 min, so an abandoned checkout doesn't starve real
     * buyers); once payment actually succeeds, the hold becomes permanent
     * (until the order is fulfilled or cancelled) and is no longer subject to
     * the expiry sweeper. This does NOT touch reserved_qty — the reservation
     * stays ACTIVE throughout, only its source/expiry change — so no balance
     * lock is needed, only the reservation row's own lock to avoid racing the
     * sweeper (which could otherwise expire it in the same instant payment
     * confirms).
     *
     * <p><b>Late payments (2026-09-28):</b> with payment confirmed by hand from a Paybill
     * statement, a customer can pay inside the window and staff can confirm hours later —
     * after the sweeper has already EXPIRED the hold and returned the stock. That used to
     * throw, leaving staff unable to mark an order paid for money already received. A lapsed
     * (EXPIRED) hold is now re-taken from current availability instead; only if the stock has
     * genuinely gone does this fail, with a message staff can act on. The caller must use the
     * RETURNED reservation, which is a new row when the old one had lapsed. A hold that was
     * released (order cancelled) or fulfilled is still refused.
     *
     * <p>Lock order is balance row then reservation, as in {@link #terminate}: this can end in
     * {@link #reserve}, and taking them the other way round could deadlock a concurrent
     * release of the same hold.
     */
    @Transactional
    public StockReservation confirmForOrder(UUID reservationId) {
        return confirmForOrder(reservationId, false);
    }

    /**
     * {@code reviving} is for a payment staff had marked failed (which released the hold) that the customer
     * has since proved they made: the released hold is re-taken from current availability exactly like a
     * lapsed one, and refused with a message staff can act on if the stock has gone. Without it a released
     * hold still means the order was cancelled, and stays refused.
     */
    @Transactional
    public StockReservation confirmForOrder(UUID reservationId, boolean reviving) {
        // §4 C7: a referenced entity missing is a 404, not a 400.
        UUID variantId = reservationRepository.findVariantIdById(reservationId)
                .orElseThrow(() -> new NotFoundException("No such reservation: " + reservationId));
        balanceRepository.lockByVariantIdAndAccount(variantId, StockAccount.SHOP)
                .orElseThrow(() -> new NotFoundException("No stock balance for reservation " + reservationId));

        StockReservation reservation = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new NotFoundException("No such reservation: " + reservationId));

        return switch (reservation.getStatus()) {
            case ACTIVE -> {
                reservation.setSource(StockReservation.ReservationSource.ONLINE_ORDER);
                reservation.setExpiresAt(null);
                yield reservationRepository.save(reservation);
            }
            case EXPIRED -> retakeLapsedHold(reservation, "expired");
            case RELEASED -> {
                if (reviving) {
                    yield retakeLapsedHold(reservation, "was released when the payment was marked failed");
                }
                throw new ConflictException(
                        "This order's stock hold was released (the order was cancelled or already dispatched), so it"
                                + " can't be marked paid. If the customer paid, refund them.");
            }
            default -> throw new ConflictException(
                    "This order's stock hold was " + reservation.getStatus().name().toLowerCase()
                            + " (the order was cancelled or already dispatched), so it can't be marked paid."
                            + " If the customer paid, refund them.");
        };
    }

    private StockReservation retakeLapsedHold(StockReservation lapsed, String why) {
        ProductVariant variant = lapsed.getVariant();
        try {
            return reserve(variant, lapsed.getQty(), StockReservation.ReservationSource.ONLINE_ORDER,
                    lapsed.getReferenceType(), lapsed.getReferenceId(), null);
        } catch (InsufficientAvailabilityException e) {
            String product = variant.displayLabel();
            throw new InsufficientAvailabilityException(
                    "This payment can't be applied: the hold on " + product + " " + why + " and the stock has since"
                            + " gone. Restock it and confirm again, or refund the customer and mark the payment failed.");
        }
    }

    /**
     * Audit finding F4 (session 3): the previous implementation read the
     * reservation with an unlocked findById, so two concurrent terminations of
     * the SAME reservation (a cart-expiry sweeper racing a payment-failure
     * webhook, say) both saw status=ACTIVE/outstanding=N from their own
     * snapshot and both decremented stock_balance.reserved_qty by N — a silent
     * double-decrement that under-reports what's actually held and reopens the
     * oversell window this whole table exists to close. The old code also
     * clamped the result to zero with .max(ZERO), which hid exactly this
     * corruption instead of surfacing it.
     *
     * <p><b>Phase 0 correction (2026-09-27):</b> the fix above was incomplete. Locking the
     * balance row first, then calling {@code lockById} on the SAME reservation this method
     * had already loaded via an unlocked {@code findById} moments earlier, does not actually
     * re-read the reservation's columns — Hibernate's persistence context returns the
     * already-managed Java object for that id, and only re-validates it against the database
     * when the entity carries a {@code @Version} (confirmed against Hibernate 6.6.53's
     * {@code EntityInitializerImpl}). The {@code SELECT ... FOR UPDATE} genuinely executes and
     * genuinely blocks on a concurrent holder, but the STATUS this method then reads back was
     * the pre-lock snapshot, not whatever the other transaction just committed — so the
     * "loser sees a non-ACTIVE status" guarantee this javadoc used to claim did not actually
     * hold for two terminations of the same reservation racing each other.
     *
     * <p>Fixed two ways, together: {@link StockReservation#getVersion()} now makes any
     * surviving staleness fail loudly (an {@code ObjectOptimisticLockingFailureException}
     * instead of a silent double-decrement) — but the real fix is not reading the reservation
     * at all before it's locked. The variant id needed to know which balance row to lock is
     * fetched via {@link com.nbh.backend.stock.repository.StockReservationRepository#findVariantIdById},
     * a scalar projection that never populates the persistence context, so {@link
     * com.nbh.backend.stock.repository.StockReservationRepository#lockById} below is this
     * reservation's first real load — genuinely fresh, not cached.
     */
    private void terminate(UUID reservationId, StockReservation.ReservationStatus target) {
        UUID variantId = reservationRepository.findVariantIdById(reservationId)
                .orElseThrow(() -> new NotFoundException("No such reservation: " + reservationId));

        StockBalance balance = balanceRepository
                .lockByVariantIdAndAccount(variantId, StockAccount.SHOP)
                .orElseThrow();

        StockReservation reservation = reservationRepository.lockById(reservationId).orElseThrow();
        if (reservation.getStatus() != StockReservation.ReservationStatus.ACTIVE) {
            return; // already terminal, or lost the race to another release/fulfill/post — idempotent
        }
        if (target == StockReservation.ReservationStatus.EXPIRED) {
            // The sweeper lists lapsed holds first and expires them one at a time, so a hold can be paid for
            // (expiry cleared) or extended between the listing and this lock. Re-check under the lock, or a
            // paid order would lose its stock and then be undispatchable.
            Instant expiresAt = reservation.getExpiresAt();
            if (expiresAt == null || expiresAt.isAfter(Instant.now(clock))) {
                return;
            }
        }

        BigDecimal outstanding = reservation.getOutstandingQty();
        if (target == StockReservation.ReservationStatus.FULFILLED) {
            reservation.setFulfilledQty(reservation.getFulfilledQty().add(outstanding));
        } else {
            // RELEASED (explicit cancel) and EXPIRED (sweeper) both give the
            // qty back to general availability the same way.
            reservation.setReleasedQty(reservation.getReleasedQty().add(outstanding));
        }
        reservation.setStatus(target);

        balance.setReservedQty(balance.getReservedQty().subtract(outstanding));
        balanceRepository.save(balance);
        reservationRepository.save(reservation);
    }
}
