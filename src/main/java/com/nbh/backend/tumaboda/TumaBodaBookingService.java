package com.nbh.backend.tumaboda;

import com.nbh.backend.common.event.TumaBodaDeliveryUpdatedEvent;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.util.PhoneNumbers;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.tumaboda.TumaBodaDtos.Delivery;
import com.nbh.backend.tumaboda.TumaBodaDtos.DeliveryRequest;
import com.nbh.backend.tumaboda.TumaBodaDtos.Recipient;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Books a TumaBoda rider for an order, the way Moments does: when staff mark the order READY (or, as a
 * safety net, once it is DISPATCHED), never at payment. A failure never breaks the staff action that
 * triggered it — it is written on the order, retried by {@link TumaBodaBookingRetryJob} on a backoff, and
 * after the last try staff see it on the order and can book again or reroute to a phone-arranged transporter.
 *
 * <p><b>NOT LIVE-TESTED against TumaBoda.</b> See {@link TumaBodaClient}: the request and response shapes
 * follow what Moments' working integration sends and reads, and are exercised here only against a fake
 * server. It needs a TumaBoda partner account for Trendysh before it can be trusted.
 *
 * <p>The network call happens with no transaction and no row lock open: a slow TumaBoda must not hold up
 * every other write to the order. So this is three steps — plan (locked, short), call, record (locked,
 * short) — and the record step re-checks that nobody booked in between. The reference sent to TumaBoda
 * is the order number, so a duplicate booking attempt comes back as the same delivery, not a second rider.
 */
@Slf4j
@Service
public class TumaBodaBookingService {

    /** Waits after each failed try. Five tries in all (the first plus these four), then staff are on their own. */
    static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(3), Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(60));
    private static final int FAILURE_TEXT_MAX = 500;

    public enum Outcome { BOOKED, ALREADY_BOOKED, NOT_DUE, WILL_RETRY, NEEDS_STAFF }

    private record Plan(Outcome skip, DeliveryRequest request) {}

    private final CustomerOrderRepository orders;
    private final TumaBodaClient client;
    private final TumaBodaConfig config;
    private final TumaBodaQuoteService quotes;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TumaBodaBookingService(CustomerOrderRepository orders, TumaBodaClient client, TumaBodaConfig config,
                                  TumaBodaQuoteService quotes, ApplicationEventPublisher eventPublisher,
                                  PlatformTransactionManager transactionManager, Clock clock) {
        this.orders = orders;
        this.client = client;
        this.config = config;
        this.quotes = quotes;
        this.eventPublisher = eventPublisher;
        // Each step is its own short transaction, never joined to a caller's: the booking runs from an
        // after-commit listener, where "joining" the just-finished transaction would silently lose the
        // writes, and an outer transaction would also keep a lock open across the network call.
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Books a rider if this is an API delivery that is due and not yet booked; otherwise says why not. Never throws for a TumaBoda failure. */
    public Outcome bookIfDue(UUID orderId) {
        Plan plan = tx.execute(status -> plan(orderId));
        if (plan.skip() != null) {
            return plan.skip();
        }

        Delivery delivery = null;
        TumaBodaException failure = null;
        try {
            delivery = client.create(plan.request());
        } catch (TumaBodaException e) {
            failure = e;
        } catch (RuntimeException e) {
            failure = new TumaBodaException("UNEXPECTED", "Unexpected error calling TumaBoda: " + e.getMessage(), true, e);
        }

        Delivery booked = delivery;
        TumaBodaException failed = failure;
        return tx.execute(status -> record(orderId, booked, failed));
    }

    /**
     * Staff pressed "book now" after a failure: start the retry ladder afresh, then book. The order must
     * not already have a live rider.
     */
    public Outcome retryNow(UUID orderId) {
        tx.executeWithoutResult(status -> {
            CustomerOrder order = lock(orderId);
            if (order.getTumabodaDeliveryId() != null) {
                throw new ConflictException("A TumaBoda rider is already booked for " + order.getOrderNo());
            }
            order.setTumabodaBookingAttempts(0);
            order.setTumabodaNextRetryAt(null);
            orders.save(order);
        });
        return bookIfDue(orderId);
    }

    /**
     * TumaBoda reported the delivery failed, returned or cancelled and staff want a fresh rider. The dead
     * delivery is cleared and the next booking uses a new reference ("SO-...-R1") — reusing the order
     * number would just hand back the dead delivery.
     */
    public Outcome restart(UUID orderId) {
        tx.executeWithoutResult(status -> {
            CustomerOrder order = lock(orderId);
            if (order.getTumabodaDeliveryId() == null) {
                throw new ConflictException(order.getOrderNo() + " has no TumaBoda delivery to restart");
            }
            if (!isDeadStatus(order.getTumabodaStatus())) {
                throw new ConflictException("The TumaBoda delivery for " + order.getOrderNo()
                        + " is still active; it can only be restarted once TumaBoda reports it failed, returned or cancelled");
            }
            order.setTumabodaRestartCount(order.getTumabodaRestartCount() + 1);
            order.setTumabodaDeliveryId(null);
            order.setTumabodaDeliveryNumber(null);
            order.setTumabodaTrackingCode(null);
            order.setTumabodaTrackingUrl(null);
            order.setTumabodaStatus(null);
            order.setTumabodaRiderId(null);
            order.setTumabodaCost(null);
            order.setTumabodaLastWebhookAt(null);
            order.setTumabodaBookingAttempts(0);
            order.setTumabodaNextRetryAt(null);
            order.setTumabodaBookingFailure(null);
            orders.save(order);
        });
        return bookIfDue(orderId);
    }

    /**
     * Gives up on the API for this order and puts it back on the phone-arranged route (V35's transporter
     * fields). Only while no rider is live: a booked TumaBoda delivery has no cancel API, so staff would
     * have to sort that out with TumaBoda directly first.
     */
    public void rerouteToManual(UUID orderId) {
        tx.executeWithoutResult(status -> {
            CustomerOrder order = lock(orderId);
            if (order.hasLiveTumabodaDelivery()) {
                throw new ConflictException("A TumaBoda rider is booked for " + order.getOrderNo()
                        + " and TumaBoda has no cancel option; contact TumaBoda before switching to a phone-arranged delivery");
            }
            order.setTumabodaRerouted(true);
            order.setTumabodaNextRetryAt(null);
            orders.save(order);
        });
    }

    static boolean isDeadStatus(String status) {
        return CustomerOrder.isDeadTumabodaStatus(status);
    }

    // ---- steps ----

    private Plan plan(UUID orderId) {
        CustomerOrder order = lock(orderId);
        if (order.getFulfilmentType() != FulfilmentType.DELIVERY_COURIER || !order.isTumabodaApiDelivery()) {
            return new Plan(Outcome.NOT_DUE, null);
        }
        if (order.getTumabodaDeliveryId() != null) {
            return new Plan(Outcome.ALREADY_BOOKED, null);
        }
        if (order.getStatus() != OrderStatus.READY && order.getStatus() != OrderStatus.DISPATCHED) {
            if (order.getTumabodaNextRetryAt() != null) {
                // Cancelled (or otherwise moved on) after a failed booking: stop the retry job coming back for it.
                order.setTumabodaNextRetryAt(null);
                orders.save(order);
            }
            return new Plan(Outcome.NOT_DUE, null);
        }
        if (!config.enabled()) {
            order.setTumabodaBookingFailure("TumaBoda isn't configured on this server, so no rider can be booked");
            order.setTumabodaNextRetryAt(null);
            orders.save(order);
            return new Plan(Outcome.NEEDS_STAFF, null);
        }

        Recipient recipient = new Recipient(
                order.getCustomerName() == null || order.getCustomerName().isBlank() ? "Customer" : order.getCustomerName(),
                internationalPhone(order.getGuestPhone()),
                order.getDeliveryAddress() == null ? "" : order.getDeliveryAddress(),
                order.getDropoffLat(), order.getDropoffLng());
        String reference = order.getTumabodaRestartCount() == 0
                ? order.getOrderNo() : order.getOrderNo() + "-R" + order.getTumabodaRestartCount();
        return new Plan(null, new DeliveryRequest(recipient, "Order " + order.getOrderNo(),
                quotes.packageFor(order.getSubtotal()), reference));
    }

    private Outcome record(UUID orderId, Delivery delivery, TumaBodaException failure) {
        CustomerOrder order = lock(orderId);
        if (order.getTumabodaDeliveryId() != null) {
            return Outcome.ALREADY_BOOKED;
        }
        if (delivery != null) {
            applyDelivery(order, delivery);
            // The booking reply is the first thing TumaBoda has told us: the reconciliation poll waits
            // for a quiet spell after THIS, rather than asking about a delivery the instant it exists.
            order.setTumabodaLastWebhookAt(Instant.now(clock));
            order.setTumabodaBookingFailure(null);
            order.setTumabodaNextRetryAt(null);
            orders.save(order);
            eventPublisher.publishEvent(new TumaBodaDeliveryUpdatedEvent(order.getId(), order.getOrderNo(), "booked"));
            return Outcome.BOOKED;
        }

        int attempts = order.getTumabodaBookingAttempts() + 1;
        order.setTumabodaBookingAttempts(attempts);
        order.setTumabodaBookingFailure(truncate(failure.getMessage()));
        boolean retry = failure.retryable() && attempts <= BACKOFF.size();
        order.setTumabodaNextRetryAt(retry ? Instant.now(clock).plus(BACKOFF.get(attempts - 1)) : null);
        orders.save(order);
        log.warn("TumaBoda booking for {} failed (attempt {}, {}): {}", order.getOrderNo(), attempts,
                retry ? "will retry" : "needs staff", failure.getMessage());
        if (!retry) {
            // A retry is routine and silent; needing staff is the moment worth interrupting them for.
            eventPublisher.publishEvent(new TumaBodaDeliveryUpdatedEvent(order.getId(), order.getOrderNo(), "booking_failed"));
        }
        return retry ? Outcome.WILL_RETRY : Outcome.NEEDS_STAFF;
    }

    /** Copies what TumaBoda reports onto the order; shared with status refreshes. */
    static void applyDelivery(CustomerOrder order, Delivery delivery) {
        order.setTumabodaDeliveryId(delivery.id());
        order.setTumabodaDeliveryNumber(delivery.deliveryNumber());
        order.setTumabodaTrackingCode(delivery.trackingCode());
        order.setTumabodaTrackingUrl(delivery.trackingUrl());
        order.setTumabodaStatus(delivery.status());
        order.setTumabodaRiderId(delivery.riderId());
        order.setTumabodaCost(delivery.totalKes());
    }

    private CustomerOrder lock(UUID orderId) {
        return orders.lockById(orderId).orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
    }

    /** TumaBoda wants a full international number ("+254711222333"); orders store it as 254711222333. */
    static String internationalPhone(String stored) {
        String digits = PhoneNumbers.normalize(stored);
        return digits == null || digits.isEmpty() ? "" : "+" + digits;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= FAILURE_TEXT_MAX ? text : text.substring(0, FAILURE_TEXT_MAX - 1) + "…";
    }
}
