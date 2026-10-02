package com.nbh.backend.live;

import com.nbh.backend.common.event.BankCreditRecordedEvent;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.event.OrderCancelledEvent;
import com.nbh.backend.common.event.OrderDeliveredEvent;
import com.nbh.backend.common.event.OrderFulfilledEvent;
import com.nbh.backend.common.event.OrderPickingStartedEvent;
import com.nbh.backend.common.event.OrderPlacedEvent;
import com.nbh.backend.common.event.OrderReadyEvent;
import com.nbh.backend.common.event.PaymentClaimSubmittedEvent;
import com.nbh.backend.common.event.PaymentConfirmedEvent;
import com.nbh.backend.common.event.PaymentFailedEvent;
import com.nbh.backend.common.event.TumaBodaDeliveryUpdatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns the existing domain events (already published elsewhere for logging — see {@code
 * EventLoggingListener}'s javadoc for why every one of these is AFTER_COMMIT and synchronous) into live
 * updates: a broadcast hint to every open staff dashboard, and — for the events that belong to one order —
 * a "something changed" push to whoever is watching that order's tracking or pay page. Neither push carries
 * anything staff-only into the customer stream; {@link LiveStreams#publishToOrder} sends no payload at all.
 *
 * <p>This class does no I/O itself: {@link LiveStreams} hands every write off to its own executor, so
 * these listener methods return immediately regardless of how many connections are open.
 */
@Component
public class LiveUpdateListener {

    private final LiveStreams streams;

    public LiveUpdateListener(LiveStreams streams) {
        this.streams = streams;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        streams.publishToStaff(new AdminEvent(AdminEvent.Type.ORDER_PLACED, event.orderNo(), event.grandTotal()));
        streams.publishToOrder(event.orderNo());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        streams.publishToStaff(new AdminEvent(AdminEvent.Type.PAYMENT_CONFIRMED, event.orderNo(), event.amount()));
        streams.publishToOrder(event.orderNo());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailed(PaymentFailedEvent event) {
        streams.publishToStaff(AdminEvent.of(AdminEvent.Type.PAYMENT_FAILED, event.orderNo()));
        streams.publishToOrder(event.orderNo());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPickingStarted(OrderPickingStartedEvent event) {
        streams.publishToStaff(AdminEvent.of(AdminEvent.Type.ORDER_UPDATED, event.orderNo()));
        streams.publishToOrder(event.orderNo());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderReady(OrderReadyEvent event) {
        streams.publishToStaff(AdminEvent.of(AdminEvent.Type.ORDER_UPDATED, event.orderNo()));
        streams.publishToOrder(event.orderNo());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderFulfilled(OrderFulfilledEvent event) {
        streams.publishToStaff(AdminEvent.of(AdminEvent.Type.ORDER_UPDATED, event.orderNo()));
        streams.publishToOrder(event.orderNo());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderDelivered(OrderDeliveredEvent event) {
        streams.publishToStaff(AdminEvent.of(AdminEvent.Type.ORDER_UPDATED, event.orderNo()));
        streams.publishToOrder(event.orderNo());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCancelled(OrderCancelledEvent event) {
        streams.publishToStaff(AdminEvent.of(AdminEvent.Type.ORDER_UPDATED, event.orderNo()));
        streams.publishToOrder(event.orderNo());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentClaimSubmitted(PaymentClaimSubmittedEvent event) {
        streams.publishToStaff(new AdminEvent(AdminEvent.Type.PAYMENT_CLAIMED, event.orderNo(), event.amountClaimed()));
        streams.publishToOrder(event.orderNo());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBankCreditRecorded(BankCreditRecordedEvent event) {
        // Staff-only: a bank credit is not any one order until it is matched (and a matched one already
        // gets its own PaymentConfirmedEvent push to that order).
        streams.publishToStaff(new AdminEvent(AdminEvent.Type.BANK_CREDIT, event.orderNo(), event.amount()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTumaBodaDeliveryUpdated(TumaBodaDeliveryUpdatedEvent event) {
        streams.publishToStaff(AdminEvent.of(AdminEvent.Type.DELIVERY_UPDATED, event.orderNo()));
        streams.publishToOrder(event.orderNo());
    }

    /** Topics changed since the last flush: a bulk edit marks the same topic thousands of times but is pushed once. */
    private final java.util.Set<DataChangedEvent.Topic> dirty = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDataChanged(DataChangedEvent event) {
        dirty.add(event.topic());
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.live.data-flush-ms:400}")
    public void flushDataChanges() {
        for (DataChangedEvent.Topic topic : java.util.List.copyOf(dirty)) {
            dirty.remove(topic);
            streams.publishToStaff(AdminEvent.dataChanged(topic.name()));
            if (topic.isPublic()) {
                streams.publishToPublic(topic.name());
            }
        }
    }
}
