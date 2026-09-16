package com.nbh.backend.stock.service;

import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.stock.entity.StockBalance;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.stock.exception.InsufficientAvailabilityException;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The anti-oversell mechanism. An online checkout and a POS sale both call
 * {@link #reserve} for the same (variant, location) — this is the exact
 * omnichannel hazard Moments Packaging never solved (its Product.reservedCount
 * column exists but is never written to anywhere in the codebase).
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
     * Attempts to reserve {@code qty} of {@code variant} at {@code location}.
     * Throws {@link InsufficientAvailabilityException} if the requested quantity
     * exceeds on-hand minus every other currently-active reservation.
     */
    @Transactional
    public StockReservation reserve(
            ProductVariant variant,
            StockLocation location,
            BigDecimal qty,
            StockReservation.ReservationSource source,
            String referenceType,
            UUID referenceId,
            Instant expiresAt
    ) {
        // 1. Balance row lock first — see class javadoc.
        StockBalance balance = balanceRepository
                .lockByVariantIdAndLocationId(variant.getId(), location.getId())
                .orElseThrow(() -> new InsufficientAvailabilityException(
                        "No stock has ever been received for " + variant.getSku() + " at " + location.getCode()));

        // 2. Active reservation rows, locked in a deterministic order.
        List<StockReservation> activeReservations =
                reservationRepository.lockActiveByVariantAndLocation(variant.getId(), location.getId());

        Instant now = Instant.now(clock);
        BigDecimal reservedOutstanding = activeReservations.stream()
                .filter(r -> r.getExpiresAt() == null || r.getExpiresAt().isAfter(now))
                .map(StockReservation::getOutstandingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal available = balance.getOnHandQty().subtract(reservedOutstanding);

        if (available.compareTo(qty) < 0) {
            throw new InsufficientAvailabilityException(
                    "Requested " + qty + " of " + variant.getSku() + " at " + location.getCode()
                            + " but only " + available + " is available");
        }

        StockReservation reservation = new StockReservation();
        reservation.setVariant(variant);
        reservation.setLocation(location);
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
     */
    @Transactional
    public void confirmForOrder(UUID reservationId) {
        // §4 C7: a referenced entity missing is a 404, not a 400.
        StockReservation reservation = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new NotFoundException("No such reservation: " + reservationId));
        if (reservation.getStatus() != StockReservation.ReservationStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Reservation " + reservationId + " is " + reservation.getStatus()
                            + ", not ACTIVE — cannot confirm it for an order (it may have expired)");
        }
        reservation.setSource(StockReservation.ReservationSource.ONLINE_ORDER);
        reservation.setExpiresAt(null);
        reservationRepository.save(reservation);
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
     * Fixed by locking the balance row FIRST (same order as everywhere else),
     * THEN re-fetching the reservation under PESSIMISTIC_WRITE and re-checking
     * status inside that lock — the loser of the race now sees a non-ACTIVE
     * status after acquiring its lock and returns without double-touching
     * anything, instead of acting on a stale snapshot. The zero-clamp is gone:
     * if this ever goes negative, the DB's CHECK(reserved_qty >= 0) constraint
     * raises loudly instead of the corruption being silently absorbed.
     */
    private void terminate(UUID reservationId, StockReservation.ReservationStatus target) {
        StockReservation snapshot = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new NotFoundException("No such reservation: " + reservationId));
        if (snapshot.getStatus() != StockReservation.ReservationStatus.ACTIVE) {
            return; // already terminal — idempotent
        }

        StockBalance balance = balanceRepository
                .lockByVariantIdAndLocationId(snapshot.getVariant().getId(), snapshot.getLocation().getId())
                .orElseThrow();

        StockReservation reservation = reservationRepository.lockById(reservationId).orElseThrow();
        if (reservation.getStatus() != StockReservation.ReservationStatus.ACTIVE) {
            return; // lost the race to another release/fulfill/post — nothing left to do
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
