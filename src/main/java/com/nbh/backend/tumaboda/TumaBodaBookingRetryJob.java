package com.nbh.backend.tumaboda;

import com.nbh.backend.order.repository.CustomerOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Retries TumaBoda bookings whose backoff has elapsed. Each order is its own booking call, so one bad row never blocks the rest. */
@Slf4j
@Component
public class TumaBodaBookingRetryJob {

    private final CustomerOrderRepository orders;
    private final TumaBodaBookingService bookings;
    private final Clock clock;

    public TumaBodaBookingRetryJob(CustomerOrderRepository orders, TumaBodaBookingService bookings, Clock clock) {
        this.orders = orders;
        this.bookings = bookings;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.tumaboda.retry-interval-ms:300000}",
            initialDelayString = "${app.tumaboda.retry-initial-delay-ms:120000}")
    public int retryDueBookings() {
        int attempted = 0;
        for (UUID id : orders.findIdsDueForTumabodaRetry(Instant.now(clock))) {
            try {
                bookings.bookIfDue(id);
                attempted++;
            } catch (RuntimeException e) {
                log.warn("TumaBoda retry for order {} failed unexpectedly: {}", id, e.getMessage());
            }
        }
        return attempted;
    }
}
