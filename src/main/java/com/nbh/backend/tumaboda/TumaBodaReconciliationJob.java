package com.nbh.backend.tumaboda;

import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.tumaboda.TumaBodaDtos.Delivery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The backstop for a missed webhook: deliveries still in progress that TumaBoda has been quiet about for
 * a while are asked for directly. Does nothing when the integration is off.
 */
@Slf4j
@Component
public class TumaBodaReconciliationJob {

    static final Duration QUIET_FOR = Duration.ofMinutes(20);

    private final CustomerOrderRepository orders;
    private final TumaBodaClient client;
    private final TumaBodaStatusService statuses;
    private final TumaBodaConfig config;
    private final Clock clock;

    public TumaBodaReconciliationJob(CustomerOrderRepository orders, TumaBodaClient client,
                                     TumaBodaStatusService statuses, TumaBodaConfig config, Clock clock) {
        this.orders = orders;
        this.client = client;
        this.statuses = statuses;
        this.config = config;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.tumaboda.reconcile-interval-ms:600000}",
            initialDelayString = "${app.tumaboda.reconcile-initial-delay-ms:180000}")
    public int reconcile() {
        if (!config.enabled()) {
            return 0;
        }
        int checked = 0;
        for (var row : orders.findTumabodaDeliveriesToPoll(Instant.now(clock).minus(QUIET_FOR))) {
            try {
                Delivery delivery = client.get(row.getTumabodaDeliveryId());
                statuses.apply(row.getTumabodaDeliveryId(), delivery.status(), delivery.riderId());
                checked++;
            } catch (RuntimeException e) {
                log.warn("TumaBoda status check for order {} failed: {}", row.getId(), e.getMessage());
            }
        }
        return checked;
    }
}
