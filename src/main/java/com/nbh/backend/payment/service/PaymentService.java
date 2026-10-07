package com.nbh.backend.payment.service;

import com.nbh.backend.common.event.PaymentConfirmedEvent;
import com.nbh.backend.common.event.PaymentFailedEvent;
import com.nbh.backend.common.exception.ConflictException;
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
import java.util.Comparator;
import java.util.List;
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
        return confirmSuccess(paymentRecordId, providerReference, rawResponse, null);
    }

    /**
     * {@code actualMethod} lets staff record how the customer really paid
     * when confirming by hand — checkout always creates the record as MPESA,
     * but under the pay-after-we-call flow it may turn out to be bank or cash.
     * Null keeps whatever the record already says (the Daraja callback path).
     */
    @Transactional
    public PaymentRecord confirmSuccess(UUID paymentRecordId, String providerReference, String rawResponse,
                                         PaymentRecord.PaymentMethod actualMethod) {
        return confirmSuccess(paymentRecordId, providerReference, rawResponse, actualMethod, false);
    }

    /**
     * {@code allowRevive} is the way back from a payment staff marked failed by mistake, when the customer then shows
     * the M-PESA message: the payment is confirmed, the order comes back and its released stock is held again. It is
     * only ever set by a deliberate "the customer did pay" request from the order panel (or by a person allocating a
     * bank credit). A stale Confirm on the Payments tab, an old customer claim being approved, or a late callback
     * must NOT bring a payment back that someone had decided was failed, so without it a FAILED payment is refused.
     */
    @Transactional
    public PaymentRecord confirmSuccess(UUID paymentRecordId, String providerReference, String rawResponse,
                                         PaymentRecord.PaymentMethod actualMethod, boolean allowRevive) {
        // §4 C2 fix: lock the row before reading its status — see
        // PaymentRecordRepository.lockById's javadoc for the race this closes.
        PaymentRecord payment = paymentRecordRepository.lockById(paymentRecordId)
                .orElseThrow(() -> new EntityNotFoundException("No payment record: " + paymentRecordId));
        if (payment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
            return payment; // already confirmed — idempotent
        }
        if (payment.getStatus() == PaymentRecord.PaymentStatus.FAILED && !allowRevive) {
            throw new IllegalStateException("This payment was marked failed. If the customer did pay, open the order and"
                    + " use \"Customer did pay — confirm\"; a failed payment is only brought back on purpose.");
        }
        boolean reviving = payment.getStatus() == PaymentRecord.PaymentStatus.FAILED;
        if (!reviving
                && payment.getStatus() != PaymentRecord.PaymentStatus.INITIATED
                && payment.getStatus() != PaymentRecord.PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment " + paymentRecordId + " is " + payment.getStatus()
                    + " — cannot confirm success from this state");
        }

        // Lock the order too, payment first and then order (then stock, in checkout's order): cancelling an
        // order and expiring one both lock it, so without this a late payment could read the order just
        // before a cancel committed and then write PAID over CANCELLED, with the cancellation already sent.
        CustomerOrder order = lockOrderOf(paymentRecordId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new ConflictException("Order " + order.getOrderNo() + " was cancelled, so it can't be marked paid."
                    + " If the customer paid, refund them.");
        }
        if (reviving && order.getStatus() != OrderStatus.PAYMENT_FAILED) {
            throw new ConflictException("Order " + order.getOrderNo() + " has moved on since this payment was marked"
                    + " failed, so the payment can't be confirmed now.");
        }

        providerReference = checkCodeNotUsedElsewhere(payment, providerReference);
        // Keep what was written when it was marked failed (who, why) instead of losing it to this confirmation's note.
        if (reviving && payment.getRawResponse() != null && !payment.getRawResponse().isBlank()) {
            // Both values are JSON already (the column is jsonb), so they nest as they are.
            rawResponse = "{\"type\":\"revived_after_failure\",\"earlier\":" + payment.getRawResponse()
                    + ",\"now\":" + (rawResponse == null || rawResponse.isBlank() ? "null" : rawResponse) + "}";
        }

        payment.setStatus(PaymentRecord.PaymentStatus.SUCCESS);
        if (actualMethod != null) {
            payment.setMethod(actualMethod);
        }
        payment.setProviderReference(providerReference);
        payment.setRawResponse(rawResponse);
        paymentRecordRepository.save(payment);

        // Every reservation moves from the time-limited checkout hold to a permanent order hold
        // — see StockReservationService.confirmForOrder's javadoc, which also covers a hold that
        // lapsed before staff confirmed (it is re-taken, so the item may point at a NEW row).
        // Variant-id order matches checkout and StockPostingService, so two orders being
        // confirmed at once cannot take the same stock balances in opposite orders.
        List<CustomerOrderItem> heldItems = order.getItems().stream()
                .filter(item -> item.getReservation() != null)
                .sorted(Comparator.comparing(item -> item.getVariant().getId()))
                .toList();
        for (CustomerOrderItem item : heldItems) {
            UUID holdId = item.getReservation().getId();
            item.setReservation(reviving
                    ? reservationService.confirmForOrder(holdId, true)
                    : reservationService.confirmForOrder(holdId));
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

    /**
     * A payment code (M-PESA or bank reference) can pay for one order only, and the database enforces that with a
     * unique index. Left to the index, a staff member who typed a code already used elsewhere (or approved a claim
     * carrying one) got "A conflicting record already exists — please retry", which is not true and not helpful.
     * Compared without regard to case or surrounding spaces, as codes are typed by hand. Returns the code to store.
     */
    private String checkCodeNotUsedElsewhere(PaymentRecord payment, String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        String trimmed = code.trim();
        // "Another payment", asked in the query itself: with rows that differ only in case a plain find-first could
        // hand back this payment's own row and hide the other one.
        paymentRecordRepository.findFirstByProviderReferenceIgnoreCaseAndIdNot(trimmed, payment.getId())
                .ifPresent(other -> {
                    throw new ConflictException("The code " + trimmed + " is already recorded as the payment for order "
                            + other.getOrder().getOrderNo() + ", and one code can only pay for one order. Check it against"
                            + " the M-PESA or bank statement.");
                });
        return trimmed;
    }

    /** The payment's order, locked. Its id comes from a scalar query so the lock is the first real load of that order. */
    private CustomerOrder lockOrderOf(UUID paymentRecordId) {
        UUID orderId = paymentRecordRepository.findOrderIdById(paymentRecordId)
                .orElseThrow(() -> new EntityNotFoundException("No payment record: " + paymentRecordId));
        return orderRepository.lockById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
    }

    @Transactional
    public PaymentRecord markFailed(UUID paymentRecordId, String reason, String rawResponse) {
        // §4 C2 fix: same lock-before-read as confirmSuccess.
        PaymentRecord payment = paymentRecordRepository.lockById(paymentRecordId)
                .orElseThrow(() -> new EntityNotFoundException("No payment record: " + paymentRecordId));
        if (payment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Payment " + paymentRecordId + " already succeeded — cannot mark failed");
        }

        CustomerOrder order = lockOrderOf(paymentRecordId); // as in confirmSuccess: payment, then order, then stock
        // A cancelled order stays cancelled: writing PAYMENT_FAILED over CANCELLED (a stale "Payment failed" click after
        // someone else cancelled) would also open the way to confirming it as paid.
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new ConflictException("Order " + order.getOrderNo() + " was cancelled, so its payment can't be marked failed.");
        }

        payment.setStatus(PaymentRecord.PaymentStatus.FAILED);
        payment.setRawResponse(rawResponse);
        paymentRecordRepository.save(payment);

        for (CustomerOrderItem item : order.getItems()) {
            if (item.getReservation() != null) {
                reservationService.release(item.getReservation().getId());
            }
        }
        order.setStatus(OrderStatus.PAYMENT_FAILED);
        orderRepository.save(order);

        eventPublisher.publishEvent(new PaymentFailedEvent(payment.getId(), order.getId(), order.getOrderNo(), reason));

        return payment;
    }
}
