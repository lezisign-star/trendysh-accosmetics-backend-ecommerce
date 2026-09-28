package com.nbh.backend.tumaboda;

import com.nbh.backend.common.event.OrderReadyEvent;
import com.nbh.backend.order.entity.FulfilmentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;

/**
 * Books the rider once the READY status has actually committed (so a rolled-back READY never books
 * anything), on TumaBoda's own executor so the staff member's request does not wait on the network.
 * {@link TumaBodaBookingService#bookIfDue} decides whether this order is one to book at all.
 */
@Slf4j
@Component
public class TumaBodaBookingListener {

    private final TumaBodaBookingService bookings;
    private final Executor executor;

    public TumaBodaBookingListener(TumaBodaBookingService bookings,
                                   @Qualifier("tumabodaTaskExecutor") Executor executor) {
        this.bookings = bookings;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderReady(OrderReadyEvent event) {
        if (event.fulfilmentType() != FulfilmentType.DELIVERY_COURIER) {
            return;
        }
        executor.execute(() -> {
            try {
                bookings.bookIfDue(event.orderId());
            } catch (RuntimeException e) {
                // Recorded failures are handled inside bookIfDue; this is only an unexpected crash.
                log.warn("TumaBoda booking for order {} crashed: {}", event.orderNo(), e.getMessage());
            }
        });
    }
}
