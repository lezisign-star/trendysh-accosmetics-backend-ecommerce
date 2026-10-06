package com.nbh.backend.order.service;

import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentClaimRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one "we still need your M-PESA code" email an unpaid order gets (owner's decision, 2026-10-06: a single reminder,
 * fifteen minutes after the order was placed, and only if no code has arrived). A customer who goes off to pay and does
 * not come back leaves an order that is not complete; this nudges them while their stock is still held, before staff
 * have to chase.
 *
 * <p>Kept apart from {@link OrderPaymentReminderSweeper}, which loops over the candidates: the sweeper calling this bean
 * is what makes each order its own transaction. Each order is locked and re-checked before anything is sent, so a code
 * arriving, a payment being confirmed or an order being cancelled between the query and the send is never emailed about.
 */
@Service
public class OrderPaymentReminderService {

    private final CustomerOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentClaimRepository claimRepository;
    private final OrderEmailService emails;
    private final Clock clock;
    private final Duration after;

    public OrderPaymentReminderService(CustomerOrderRepository orderRepository,
                                       PaymentRecordRepository paymentRecordRepository,
                                       PaymentClaimRepository claimRepository,
                                       OrderEmailService emails,
                                       Clock clock,
                                       @Value("${app.orders.payment-reminder-after-minutes:15}") long afterMinutes) {
        this.orderRepository = orderRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.claimRepository = claimRepository;
        this.emails = emails;
        this.clock = clock;
        this.after = Duration.ofMinutes(afterMinutes);
    }

    /** Ids of the orders that are due a reminder right now. */
    public List<UUID> dueOrderIds() {
        Instant now = Instant.now(clock);
        return orderRepository.findIdsDueForPaymentReminder(now.minus(after), now);
    }

    /** Sends the reminder if the order is still waiting for its code; true when one was sent. */
    @Transactional
    public boolean remindIfStillWaiting(UUID orderId) {
        CustomerOrder order = orderRepository.lockById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT
                || order.getPaymentReminderSentAt() != null || order.isMockData()) {
            return false;
        }
        Instant now = Instant.now(clock);
        PaymentRecord payment = paymentRecordRepository.findByOrderId(orderId).stream()
                .filter(p -> p.getPaymentReference() != null
                        && (p.getStatus() == PaymentRecord.PaymentStatus.INITIATED
                            || p.getStatus() == PaymentRecord.PaymentStatus.PENDING))
                .findFirst().orElse(null);
        // No open payment window means the stock hold has gone: a reminder would send them to pay for nothing.
        if (payment == null || payment.getExpiresAt() == null || !payment.getExpiresAt().isAfter(now)) {
            return false;
        }
        // Any code at all, even a rejected one, means staff have it in hand; the reminder is for silence.
        if (claimRepository.countByPaymentId(payment.getId()) > 0) {
            return false;
        }
        order.setPaymentReminderSentAt(now);
        orderRepository.save(order);
        emails.paymentReminder(order, payment);
        return true;
    }
}
