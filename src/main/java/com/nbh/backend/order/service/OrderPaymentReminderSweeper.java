package com.nbh.backend.order.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runs every minute, finding the unpaid orders whose customer has gone quiet (no M-PESA code a quarter of an hour after
 * placing the order) and asking {@link OrderPaymentReminderService} to nudge each once.
 */
@Slf4j
@Component
public class OrderPaymentReminderSweeper {

    private final OrderPaymentReminderService reminders;

    public OrderPaymentReminderSweeper(OrderPaymentReminderService reminders) {
        this.reminders = reminders;
    }

    /** One pass; a single bad order never stops the rest. Returns how many reminders went out. */
    @Scheduled(fixedDelayString = "${app.orders.reminder-sweep-interval-ms:60000}",
            initialDelayString = "${app.orders.reminder-sweep-initial-delay-ms:45000}")
    public int remindQuietCustomers() {
        int sent = 0;
        for (UUID id : reminders.dueOrderIds()) {
            try {
                if (reminders.remindIfStillWaiting(id)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to send the payment reminder for order {}: {}", id, e.getMessage());
            }
        }
        if (sent > 0) {
            log.info("Sent {} payment-code reminder(s)", sent);
        }
        return sent;
    }
}
