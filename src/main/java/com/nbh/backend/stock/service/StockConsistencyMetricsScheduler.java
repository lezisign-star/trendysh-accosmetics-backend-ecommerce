package com.nbh.backend.stock.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tier 2 item 13 of the session-7 improvement plan: F7's consistency check
 * (§ StockBalanceRebuildService) existed only as a manual admin endpoint —
 * "we believe the ledger and cache agree" had no monitoring behind it. This
 * runs the same check on a schedule and exposes its result count as a
 * Micrometer gauge (nbh_stock_consistency_mismatches), so a real divergence
 * is caught within an hour rather than whenever someone happens to hit the
 * endpoint by hand. A non-zero value here means either a genuine bug in
 * StockPostingService or (far more likely, and worth checking first) manual
 * data surgery against the database outside the application.
 */
@Slf4j
@Component
public class StockConsistencyMetricsScheduler {

    private final StockBalanceRebuildService rebuildService;
    private final AtomicInteger mismatchCount = new AtomicInteger(0);

    public StockConsistencyMetricsScheduler(StockBalanceRebuildService rebuildService, MeterRegistry meterRegistry) {
        this.rebuildService = rebuildService;
        Gauge.builder("nbh_stock_consistency_mismatches", mismatchCount, AtomicInteger::get)
                .description("Count of stock_balance rows (plus double-entry violations) that disagree "
                        + "with a from-ledger recomputation. Should always be zero.")
                .register(meterRegistry);
    }

    /** Runs once at boot so the gauge reflects reality immediately, not only after the first interval elapses. */
    @PostConstruct
    public void runOnStartup() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${app.stock.consistency-check-interval-ms:3600000}")
    public void refresh() {
        StockBalanceRebuildService.RebuildReport report = rebuildService.check();
        int count = report.balanceMismatches().size() + report.doubleEntryViolations().size();
        mismatchCount.set(count);
        if (count > 0) {
            log.error("Stock consistency check found {} mismatch(es): {} balance mismatches, "
                            + "{} double-entry violations — see StockBalanceRebuildService.RebuildReport for detail",
                    count, report.balanceMismatches().size(), report.doubleEntryViolations().size());
        }
    }
}
