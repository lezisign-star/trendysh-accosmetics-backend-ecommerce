package com.nbh.backend.stock.insights;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/** Sends the morning stock digest at 07:00 Nairobi time (once a day — see {@code StockAlertSettings.lastDigestOn}). */
@Slf4j
@Component
public class StockDigestScheduler {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");

    private final StockAlertService alertService;

    public StockDigestScheduler(StockAlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(cron = "${app.stock.digest-cron:0 0 7 * * *}", zone = "Africa/Nairobi")
    public void sendDailyDigest() {
        try {
            int items = alertService.sendDigestIfDue(LocalDate.now(SHOP_ZONE));
            if (items > 0) log.info("Stock digest sent ({} item(s))", items);
        } catch (Exception e) {
            log.error("Stock digest failed: {}", e.getMessage(), e);
        }
    }
}
