package com.nbh.backend.stock.service;

import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockBalance;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockLedgerEntryRepository;
import com.nbh.backend.stock.repository.StockReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Recomputes on-hand purely from stock_ledger_entry and compares it against
 * the cached stock_balance table — the Phase-1 acceptance test the
 * architecture doc calls for (§4.4, §6): "a rebuild-from-ledger reproduces
 * balances exactly."
 *
 * Audit finding F7 (session 3): the previous version of this class was never
 * called from anywhere (no endpoint, no job, no test) and, worse, did not
 * actually rebuild anything — it compared stock_balance.on_hand_qty against
 * findLatestQtyAfter, which reads the SAME denormalised running balance the
 * write path already wrote. That can never disagree with itself; it is not a
 * consistency check. This version does a real single-pass reconciliation via
 * SUM(qty_change) grouped by (variant, account) — one query, not one query
 * per balance row — and also checks the double-entry invariant (§4.2):
 * summed across every account, each variant's total qty_change must be
 * exactly zero. "Account" replaced "location" 2026-09-24 when branches were
 * removed (founder decision — see HANDOFF.md); the reconciliation logic and
 * its guarantees are unchanged, only the key.
 */
@Service
public class StockBalanceRebuildService {

    /** An ACTIVE hold against one of these has outlived the order it was for — see checkOrphanedHolds. */
    private static final Set<OrderStatus> TERMINAL_WITHOUT_ACTIVE_HOLD =
            EnumSet.of(OrderStatus.CANCELLED, OrderStatus.PAYMENT_FAILED, OrderStatus.EXPIRED, OrderStatus.DELIVERED);

    private final StockBalanceRepository balanceRepository;
    private final StockLedgerEntryRepository ledgerRepository;
    private final StockReservationRepository reservationRepository;
    private final CustomerOrderRepository orderRepository;

    public StockBalanceRebuildService(StockBalanceRepository balanceRepository,
                                       StockLedgerEntryRepository ledgerRepository,
                                       StockReservationRepository reservationRepository,
                                       CustomerOrderRepository orderRepository) {
        this.balanceRepository = balanceRepository;
        this.ledgerRepository = ledgerRepository;
        this.reservationRepository = reservationRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public RebuildReport check() {
        Map<VariantAccount, BigDecimal> ledgerOnHand = new HashMap<>();
        for (Object[] row : ledgerRepository.sumQtyChangeByVariantAndAccount()) {
            ledgerOnHand.put(new VariantAccount((UUID) row[0], (StockAccount) row[1]), (BigDecimal) row[2]);
        }

        List<StockBalance> balances = balanceRepository.findAll();
        Map<VariantAccount, StockBalance> balanceByKey = new HashMap<>();
        for (StockBalance b : balances) {
            balanceByKey.put(new VariantAccount(b.getVariant().getId(), b.getAccount()), b);
        }

        List<Mismatch> mismatches = new ArrayList<>();

        // Every ledger-derived pair must match its cached balance exactly.
        for (Map.Entry<VariantAccount, BigDecimal> entry : ledgerOnHand.entrySet()) {
            StockBalance balance = balanceByKey.get(entry.getKey());
            BigDecimal expected = entry.getValue();
            BigDecimal actual = balance != null ? balance.getOnHandQty() : BigDecimal.ZERO;
            if (expected.compareTo(actual) != 0) {
                mismatches.add(new Mismatch(entry.getKey().variantId(), entry.getKey().account(), expected, actual));
            }
        }

        // Any cached balance with no ledger entries at all is itself a mismatch
        // (it should not exist, or should be exactly zero).
        for (Map.Entry<VariantAccount, StockBalance> entry : balanceByKey.entrySet()) {
            if (!ledgerOnHand.containsKey(entry.getKey()) && entry.getValue().getOnHandQty().signum() != 0) {
                mismatches.add(new Mismatch(entry.getKey().variantId(), entry.getKey().account(),
                        BigDecimal.ZERO, entry.getValue().getOnHandQty()));
            }
        }

        List<UUID> doubleEntryViolations = new ArrayList<>();
        for (Object[] row : ledgerRepository.sumQtyChangeByVariant()) {
            BigDecimal total = (BigDecimal) row[1];
            if (total.signum() != 0) {
                doubleEntryViolations.add((UUID) row[0]);
            }
        }

        return new RebuildReport(mismatches, doubleEntryViolations,
                checkReservedQtyMismatches(balanceByKey), checkOrphanedHolds());
    }

    /**
     * Phase 0 addition (2026-09-27): stock_balance.reserved_qty is a cache of
     * SUM(outstanding) over every ACTIVE reservation for that variant — this is the reverse
     * direction check nothing was doing before (StockReservationService's own fix this same
     * day closes the write-time race that could desync the two; this is the ongoing detector
     * for any other way they could still drift apart).
     */
    private List<ReservedQtyMismatch> checkReservedQtyMismatches(Map<VariantAccount, StockBalance> balanceByKey) {
        Map<UUID, BigDecimal> outstandingByVariant = new HashMap<>();
        for (StockReservationRepository.VariantOutstanding row : reservationRepository.sumOutstandingByVariant()) {
            outstandingByVariant.put(row.getVariantId(), row.getOutstanding());
        }

        List<ReservedQtyMismatch> mismatches = new ArrayList<>();
        Set<UUID> variantsWithShopBalance = new HashSet<>();
        for (Map.Entry<VariantAccount, StockBalance> entry : balanceByKey.entrySet()) {
            if (entry.getKey().account() != StockAccount.SHOP) continue;
            variantsWithShopBalance.add(entry.getKey().variantId());
            BigDecimal cached = entry.getValue().getReservedQty();
            BigDecimal expected = outstandingByVariant.getOrDefault(entry.getKey().variantId(), BigDecimal.ZERO);
            if (cached.compareTo(expected) != 0) {
                mismatches.add(new ReservedQtyMismatch(entry.getKey().variantId(), expected, cached));
            }
        }
        // A variant with active reservations but no SHOP balance row at all shouldn't be
        // possible (reserve() requires an existing balance row), but report it rather than
        // silently skip it if it ever happens.
        for (Map.Entry<UUID, BigDecimal> entry : outstandingByVariant.entrySet()) {
            if (!variantsWithShopBalance.contains(entry.getKey()) && entry.getValue().signum() != 0) {
                mismatches.add(new ReservedQtyMismatch(entry.getKey(), entry.getValue(), BigDecimal.ZERO));
            }
        }
        return mismatches;
    }

    /**
     * Phase 0 addition (2026-09-27): an ACTIVE hold against an order that has since reached a
     * terminal state (cancelled, payment failed, expired, or already delivered — which should
     * have gone through StockPostingService.post and come out FULFILLED, not still ACTIVE) is
     * stock nobody can sell that also isn't attached to anything real anymore. No direct JPA
     * relationship exists between StockReservation and CustomerOrder (the reference is
     * polymorphic — referenceType/referenceId, since a POS ticket or a manual hold uses the
     * same table), so this is a batched two-step check rather than a single query.
     */
    private List<UUID> checkOrphanedHolds() {
        List<StockReservation> activeOrderHolds = reservationRepository.findByStatusAndReferenceType(
                StockReservation.ReservationStatus.ACTIVE, "CUSTOMER_ORDER");
        if (activeOrderHolds.isEmpty()) {
            return List.of();
        }
        List<UUID> orderIds = activeOrderHolds.stream().map(StockReservation::getReferenceId).distinct().toList();
        Map<UUID, OrderStatus> statusByOrderId = new HashMap<>();
        for (CustomerOrder order : orderRepository.findAllById(orderIds)) {
            statusByOrderId.put(order.getId(), order.getStatus());
        }

        List<UUID> orphanedReservationIds = new ArrayList<>();
        for (StockReservation hold : activeOrderHolds) {
            OrderStatus status = statusByOrderId.get(hold.getReferenceId());
            // A missing order (null status) is its own, separate kind of problem, but not one
            // this check's name promises to find — reported here anyway since it's free to
            // detect and strictly worse than a merely-terminal order.
            if (status == null || TERMINAL_WITHOUT_ACTIVE_HOLD.contains(status)) {
                orphanedReservationIds.add(hold.getId());
            }
        }
        return orphanedReservationIds;
    }

    private record VariantAccount(UUID variantId, StockAccount account) {}

    public record Mismatch(UUID variantId, StockAccount account, BigDecimal ledgerOnHand, BigDecimal cachedOnHand) {}

    public record ReservedQtyMismatch(UUID variantId, BigDecimal expectedFromReservations, BigDecimal cachedOnBalance) {}

    public record RebuildReport(List<Mismatch> balanceMismatches, List<UUID> doubleEntryViolations,
                                 List<ReservedQtyMismatch> reservedQtyMismatches, List<UUID> orphanedHoldIds) {
        public boolean isConsistent() {
            return balanceMismatches.isEmpty() && doubleEntryViolations.isEmpty()
                    && reservedQtyMismatches.isEmpty() && orphanedHoldIds.isEmpty();
        }
    }
}
