package com.nbh.backend.stock.service;

import com.nbh.backend.stock.entity.StockAccount;
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
 * SUM(qty_change) grouped by (variant, account) — one query, not one query
 * per balance row — and also checks the double-entry invariant (§4.2):
 * summed across every account, each variant's total qty_change must be
 * exactly zero. "Account" replaced "location" 2026-09-24 when branches were
 * removed (founder decision — see HANDOFF.md); the reconciliation logic and
 * its guarantees are unchanged, only the key.
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

        return new RebuildReport(mismatches, doubleEntryViolations);
    }

    private record VariantAccount(UUID variantId, StockAccount account) {}

    public record Mismatch(UUID variantId, StockAccount account, BigDecimal ledgerOnHand, BigDecimal cachedOnHand) {}

    public record RebuildReport(List<Mismatch> balanceMismatches, List<UUID> doubleEntryViolations) {
        public boolean isConsistent() {
            return balanceMismatches.isEmpty() && doubleEntryViolations.isEmpty();
        }
    }
}
