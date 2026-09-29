package com.nbh.backend.stock.insights;

import com.nbh.backend.common.event.LowStockThresholdCrossedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails staff when a sale (or any movement) takes a variant below its reorder level. Runs after the
 * stock movement has committed and on its own thread, because sending email is a network call that
 * must never slow down or fail a checkout or a fulfilment.
 */
@Slf4j
@Component
public class LowStockAlertListener {

    private final StockAlertService alertService;

    public LowStockAlertListener(StockAlertService alertService) {
        this.alertService = alertService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLowStock(LowStockThresholdCrossedEvent event) {
        try {
            alertService.sendLowStockAlert(event);
        } catch (Exception e) {
            log.error("Low-stock alert for {} failed: {}", event.sku(), e.getMessage(), e);
        }
    }
}
