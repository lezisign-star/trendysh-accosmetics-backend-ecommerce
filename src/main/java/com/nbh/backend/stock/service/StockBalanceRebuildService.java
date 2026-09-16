package com.nbh.backend.stock.service;

import com.nbh.backend.stock.entity.StockBalance;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockLedgerEntryRepository;
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
 * SUM(qty_change) grouped by (variant, location) — one query, not one query
 * per balance row — and also checks the double-entry invariant (§4.2):
 * summed across every location, each variant's total qty_change must be
 * exactly zero.
 */
@Service
public class StockBalanceRebuildService {

    private final StockBalanceRepository balanceRepository;
    private final StockLedgerEntryRepository ledgerRepository;

    public StockBalanceRebuildService(StockBalanceRepository balanceRepository,
                                       StockLedgerEntryRepository ledgerRepository) {
        this.balanceRepository = balanceRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional(readOnly = true)
    public RebuildReport check() {
        Map<VariantLocation, BigDecimal> ledgerOnHand = new HashMap<>();
        for (Object[] row : ledgerRepository.sumQtyChangeByVariantAndLocation()) {
            ledgerOnHand.put(new VariantLocation((UUID) row[0], (UUID) row[1]), (BigDecimal) row[2]);
        }

        List<StockBalance> balances = balanceRepository.findAll();
        Map<VariantLocation, StockBalance> balanceByKey = new HashMap<>();
        for (StockBalance b : balances) {
            balanceByKey.put(new VariantLocation(b.getVariant().getId(), b.getLocation().getId()), b);
        }

        List<Mismatch> mismatches = new ArrayList<>();

        // Every ledger-derived pair must match its cached balance exactly.
        for (Map.Entry<VariantLocation, BigDecimal> entry : ledgerOnHand.entrySet()) {
            StockBalance balance = balanceByKey.get(entry.getKey());
            BigDecimal expected = entry.getValue();
            BigDecimal actual = balance != null ? balance.getOnHandQty() : BigDecimal.ZERO;
            if (expected.compareTo(actual) != 0) {
                mismatches.add(new Mismatch(entry.getKey().variantId(), entry.getKey().locationId(), expected, actual));
            }
        }

        // Any cached balance with no ledger entries at all is itself a mismatch
        // (it should not exist, or should be exactly zero).
        for (Map.Entry<VariantLocation, StockBalance> entry : balanceByKey.entrySet()) {
            if (!ledgerOnHand.containsKey(entry.getKey()) && entry.getValue().getOnHandQty().signum() != 0) {
                mismatches.add(new Mismatch(entry.getKey().variantId(), entry.getKey().locationId(),
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

        return new RebuildReport(mismatches, doubleEntryViolations);
    }

    private record VariantLocation(UUID variantId, UUID locationId) {}

    public record Mismatch(UUID variantId, UUID locationId, BigDecimal ledgerOnHand, BigDecimal cachedOnHand) {}

    public record RebuildReport(List<Mismatch> balanceMismatches, List<UUID> doubleEntryViolations) {
        public boolean isConsistent() {
            return balanceMismatches.isEmpty() && doubleEntryViolations.isEmpty();
        }
    }
}
