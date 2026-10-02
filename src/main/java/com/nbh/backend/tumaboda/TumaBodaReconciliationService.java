package com.nbh.backend.tumaboda;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Checking Trendysh's own settlement ledger against what TumaBoda's own credit-line/balance API
 * says is owed — the real HTTP call needs TumaBoda API credentials and documentation that don't
 * exist yet (TUMABODA_PARITY_PLAN.md Section B). Stubbed behind this interface so the rest of the
 * settlement ledger doesn't block on it: {@link NoopTumaBodaReconciliationService} is the only
 * implementation today, returning {@code Optional.empty()} always; swap in a real one once
 * credentials exist.
 */
public interface TumaBodaReconciliationService {

    /** TumaBoda's own view of the outstanding balance, if it could be checked. Empty when not configured. */
    Optional<BigDecimal> remoteBalanceOwed();
}
