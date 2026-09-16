package com.nbh.backend.payment.service;

import com.nbh.backend.common.event.PaymentConfirmedEvent;
import com.nbh.backend.common.event.PaymentFailedEvent;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.stock.service.StockReservationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Both the real Daraja callback (once wired — see {@link DarajaPaymentGateway}'s
 * javadoc for what's missing) and a staff-triggered manual confirmation (cash,
 * bank transfer, or an M-Pesa till payment confirmed by phone) end up calling
 * {@link #confirmSuccess}/{@link #markFailed} — there is exactly one place
 * that flips an order from PENDING_PAYMENT, regardless of how confirmation
 * arrived.
 */
@Service
public class PaymentService {

    private final PaymentRecordRepository paymentRecordRepository;
    private final CustomerOrderRepository orderRepository;
    private final StockReservationService reservationService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentService(PaymentRecordRepository paymentRecordRepository,
                           CustomerOrderRepository orderRepository,
                           StockReservationService reservationService,
                           Clock clock,
                           ApplicationEventPublisher eventPublisher) {
        this.paymentRecordRepository = paymentRecordRepository;
        this.orderRepository = orderRepository;
        this.reservationService = reservationService;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public PaymentRecord confirmSuccess(UUID paymentRecordId, String providerReference, String rawResponse) {
        // §4 C2 fix: lock the row before reading its status — see
        // PaymentRecordRepository.lockById's javadoc for the race this closes.
        PaymentRecord payment = paymentRecordRepository.lockById(paymentRecordId)
                .orElseThrow(() -> new EntityNotFoundException("No payment record: " + paymentRecordId));
        if (payment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
            return payment; // already confirmed — idempotent
        }
        if (payment.getStatus() != PaymentRecord.PaymentStatus.INITIATED
                && payment.getStatus() != PaymentRecord.PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment " + paymentRecordId + " is " + payment.getStatus()
                    + " — cannot confirm success from this state");
        }

        payment.setStatus(PaymentRecord.PaymentStatus.SUCCESS);
        payment.setProviderReference(providerReference);
        payment.setRawResponse(rawResponse);
        paymentRecordRepository.save(payment);

        CustomerOrder order = payment.getOrder();
        // Every reservation moves from the 15-minute cart hold to a permanent
        // order hold — see StockReservationService.confirmForOrder's javadoc.
        for (CustomerOrderItem item : order.getItems()) {
            if (item.getReservation() != null) {
                reservationService.confirmForOrder(item.getReservation().getId());
            }
        }
        order.setStatus(OrderStatus.PAID);
        order.setPlacedAt(Instant.now(clock));
        orderRepository.save(order);

        // §2: published inside this @Transactional method, delivered
        // AFTER_COMMIT — see EventLoggingListener's javadoc for why (confirmForOrder
        // above can still throw on a reservation that lost the expiry race,
        // rolling back everything including the SUCCESS/PAID writes just made).
        eventPublisher.publishEvent(new PaymentConfirmedEvent(
                payment.getId(), order.getId(), order.getOrderNo(), payment.getAmount(),
                payment.getMethod(), providerReference));

        return payment;
    }

    @Transactional
    public PaymentRecord markFailed(UUID paymentRecordId, String reason, String rawResponse) {
        // §4 C2 fix: same lock-before-read as confirmSuccess.
        PaymentRecord payment = paymentRecordRepository.lockById(paymentRecordId)
                .orElseThrow(() -> new EntityNotFoundException("No payment record: " + paymentRecordId));
        if (payment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Payment " + paymentRecordId + " already succeeded — cannot mark failed");
        }

        payment.setStatus(PaymentRecord.PaymentStatus.FAILED);
        payment.setRawResponse(rawResponse);
        paymentRecordRepository.save(payment);

        CustomerOrder order = payment.getOrder();
        for (CustomerOrderItem item : order.getItems()) {
            if (item.getReservation() != null) {
                reservationService.release(item.getReservation().getId());
            }
        }
        order.setStatus(OrderStatus.PAYMENT_FAILED);
        orderRepository.save(order);

        eventPublisher.publishEvent(new PaymentFailedEvent(payment.getId(), order.getId(), reason));

        return payment;
    }
}
