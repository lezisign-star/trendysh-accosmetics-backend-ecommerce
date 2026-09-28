package com.nbh.backend.order.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Runs every minute, right behind the stock-hold expiry sweeper, expiring unpaid orders that no longer hold stock. */
@Slf4j
@Component
public class OrderExpirySweeper {

    private final OrderExpiryService expiryService;

    public OrderExpirySweeper(OrderExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    /** One pass over every unpaid order; a single bad row never stops the rest. Returns how many expired. */
    @Scheduled(fixedDelayString = "${app.orders.expiry-sweep-interval-ms:60000}",
            initialDelayString = "${app.orders.expiry-sweep-initial-delay-ms:30000}")
    public int sweepUnpaidOrders() {
        int expired = 0;
        for (UUID id : expiryService.unpaidOrderIds()) {
            try {
                if (expiryService.expireIfLapsed(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to expire order {}: {}", id, e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Expired {} unpaid order(s) whose stock holds had lapsed", expired);
        }
        return expired;
    }
}
