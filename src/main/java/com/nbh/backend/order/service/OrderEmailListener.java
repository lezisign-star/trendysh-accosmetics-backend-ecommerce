package com.nbh.backend.order.service;

import com.nbh.backend.common.event.OrderPlacedEvent;
import com.nbh.backend.common.event.PaymentConfirmedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns the events the order and payment code already publishes into customer emails, once the transaction that
 * published them has committed. A listener that threw would fail a request whose order is already saved, so every
 * failure here is logged and swallowed: a missing email is a pity, a failed checkout is a lost sale.
 */
@Slf4j
@Component
public class OrderEmailListener {

    private final OrderEmailService emails;

    public OrderEmailListener(OrderEmailService emails) {
        this.emails = emails;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        try {
            emails.orderPlaced(event.orderId());
        } catch (RuntimeException e) {
            log.error("Couldn't prepare the order-placed email for {}: {}", event.orderNo(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        try {
            emails.paymentReceived(event.orderId());
        } catch (RuntimeException e) {
            log.error("Couldn't prepare the payment-received email for {}: {}", event.orderNo(), e.getMessage());
        }
    }
}
