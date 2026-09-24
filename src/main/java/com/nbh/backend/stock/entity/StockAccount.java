package com.nbh.backend.stock.entity;

/**
 * Replaces {@code Branch}/{@code StockLocation} (removed 2026-09-24, founder decision —
 * see HANDOFF.md). There is exactly one physical place stock exists: SHOP. The other
 * three values are the same virtual bookkeeping counterparts the old {@code LocationType}
 * had (SUPPLIER/CUSTOMER/ADJUSTMENT) — every stock movement still has two sides, so the
 * ledger's double-entry correctness (everything sums to zero per variant, see
 * StockBalanceRebuildService) is unchanged. This is an enum, not a table: no rows, no
 * branch-shaped concept anywhere, nothing to seed or manage.
 */
public enum StockAccount {
    SHOP(true),
    SUPPLIER(false),
    CUSTOMER(false),
    ADJUSTMENT(false);

    private final boolean physical;

    StockAccount(boolean physical) {
        this.physical = physical;
    }

    /** Only SHOP has a real, physically countable shelf. */
    public boolean isPhysical() {
        return physical;
    }
}
