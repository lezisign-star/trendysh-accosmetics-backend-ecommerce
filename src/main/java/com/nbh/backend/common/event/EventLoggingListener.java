package com.nbh.backend.common.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * §2: v1 listeners for all five NBH backend events — log-only, deliberately.
 * When a real consumer exists (order-confirmation email, a TumaBoda dispatch
 * call, a purchasing alert), that listener belongs in its own class in the
 * relevant domain package, not bolted on here — this class is only the v1
 * seam proving the publish side works before there is anything real to wire.
 *
 * Every method is annotated {@code phase = AFTER_COMMIT} EXPLICITLY even
 * though that is {@link TransactionalEventListener}'s default — it is the
 * single most important property of this design (§2.7: the publishing
 * transaction can still roll back after the event is published in-method —
 * CheckoutService.checkout after the order row is saved but before every line
 * is reserved, PaymentService.confirmSuccess after SUCCESS is set but before
 * confirmForOrder finishes, OrderFulfilmentService.fulfil right up to the
 * posting call — an in-transaction listener would act on a transaction that
 * never actually committed) and should be visible at every listener, not
 * inherited from a default a future reader has to look up.
 *
 * Synchronous, not {@code @Async}, for the same reason: these listeners do
 * microseconds of logging, so running them on the (already-committed)
 * request thread costs nothing. The moment a listener does real I/O (an
 * email send, an HTTP call to a courier), it must become {@code @Async} with
 * an explicitly configured executor and {@code @Transactional(propagation =
 * REQUIRES_NEW)} if it writes to the DB — see the plan's §2.8 for the three
 * things that must change together when that happens.
 *
 * Payloads are IDs and scalars only (never entities) precisely because
 * {@code application.yml}'s {@code open-in-view: false} means a lazy field
 * on a detached entity throws the instant an AFTER_COMMIT listener (running
 * after the original Hibernate session has closed) touches it — the same bug
 * class sessions 3 and 6 already hit via DTO mapping outside a transaction.
 */
@Slf4j
@Component
public class EventLoggingListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        log.info("Order placed: {} ({} item(s), grand total {}) for {}",
                event.orderNo(), event.itemCount(), event.grandTotal(), event.guestEmail());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        log.info("Payment confirmed: order {} paid {} via {} (provider ref {})",
                event.orderNo(), event.amount(), event.method(), event.providerReference());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailed(PaymentFailedEvent event) {
        log.info("Payment failed for order {}: {}", event.orderId(), event.reason());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderFulfilled(OrderFulfilledEvent event) {
        log.info("Order fulfilled: {} dispatched from branch {} ({})",
                event.orderNo(), event.branchId(), event.fulfilmentType());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLowStockThresholdCrossed(LowStockThresholdCrossedEvent event) {
        log.warn("Low stock: {} at location {} dropped from {} to {} (reorder level {})",
                event.sku(), event.locationId(), event.onHandBefore(), event.onHandAfter(), event.reorderLevel());
    }
}
