package com.nbh.backend.tumaboda;

import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.order.service.OrderFulfilmentService;
import com.nbh.backend.tumaboda.TumaBodaDtos.Delivery;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Applies what TumaBoda reports about a delivery — from its webhook, from the reconciliation poll, or
 * from staff pressing "check now" — and moves the order along where the report implies it: the rider
 * collecting the parcel is the order being DISPATCHED (which posts the stock movement), and TumaBoda
 * confirming delivery is the order being DELIVERED. A failed, returned or cancelled delivery does NOT
 * touch the order's status; it is stored and shown to staff, who decide (book another rider, or reroute to
 * a phone-arranged one). Idempotent, and it ignores a report older than what is already recorded, since
 * webhooks can arrive out of order.
 *
 * <p>Recording the report and moving the order are separate transactions on purpose: if posting the stock
 * movement fails (it should not — the stock is held for this order), the fact that TumaBoda said the rider
 * has the parcel must still be saved.
 */
@Slf4j
@Service
public class TumaBodaStatusService {

    private enum Next { NONE, DISPATCH, DISPATCH_THEN_DELIVER, DELIVER }

    private record Applied(UUID orderId, Next next, String status) {}

    private final CustomerOrderRepository orders;
    private final OrderFulfilmentService fulfilment;
    private final TumaBodaClient client;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TumaBodaStatusService(CustomerOrderRepository orders, OrderFulfilmentService fulfilment,
                                 TumaBodaClient client, PlatformTransactionManager transactionManager, Clock clock) {
        this.orders = orders;
        this.fulfilment = fulfilment;
        this.client = client;
        // Never joined to a caller's transaction: recording the report must commit on its own (see the class javadoc).
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Records a status report for a delivery id and moves its order along. An unknown delivery id is logged and ignored. */
    public void apply(String deliveryId, String status, String riderId) {
        if (deliveryId == null || deliveryId.isBlank() || status == null || status.isBlank()) {
            return;
        }
        Applied applied = tx.execute(s -> record(deliveryId, status.trim(), riderId));
        if (applied != null) {
            moveOrder(applied);
        }
    }

    /** Asks TumaBoda for this order's delivery now and applies the answer ("check now" in the admin panel). */
    public void checkNow(UUID orderId) {
        String deliveryId = tx.execute(s -> orders.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId)).getTumabodaDeliveryId());
        if (deliveryId == null) {
            throw new ConflictException("No TumaBoda rider has been booked for this order yet");
        }
        Delivery delivery = client.get(deliveryId);
        apply(delivery.id() == null ? deliveryId : delivery.id(), delivery.status(), delivery.riderId());
    }

    // ---- steps ----

    private Applied record(String deliveryId, String status, String riderId) {
        UUID orderId = orders.findIdByTumabodaDeliveryId(deliveryId).orElse(null);
        if (orderId == null) {
            log.warn("TumaBoda reported '{}' for delivery {}, which matches no order", status, deliveryId);
            return null;
        }
        CustomerOrder order = orders.lockById(orderId).orElseThrow();
        order.setTumabodaLastWebhookAt(Instant.now(clock));
        if (riderId != null && !riderId.isBlank()) {
            order.setTumabodaRiderId(riderId);
        }
        if (isOlderThanRecorded(order.getTumabodaStatus(), status)) {
            orders.save(order);
            return new Applied(orderId, Next.NONE, status);
        }
        order.setTumabodaStatus(status);
        orders.save(order);
        return new Applied(orderId, decide(order.getStatus(), status), status);
    }

    private void moveOrder(Applied applied) {
        if (applied.next() == Next.NONE) {
            return;
        }
        try {
            if (applied.next() == Next.DISPATCH || applied.next() == Next.DISPATCH_THEN_DELIVER) {
                fulfilment.fulfil(applied.orderId(), null);
            }
            if (applied.next() == Next.DELIVER || applied.next() == Next.DISPATCH_THEN_DELIVER) {
                fulfilment.markDelivered(applied.orderId());
            }
        } catch (RuntimeException e) {
            log.error("TumaBoda reported '{}' for order {} but the order could not be moved on: {}",
                    applied.status(), applied.orderId(), e.getMessage());
            tx.executeWithoutResult(s -> orders.lockById(applied.orderId()).ifPresent(order -> {
                order.setTumabodaBookingFailure("TumaBoda reports '" + applied.status()
                        + "' but the order could not be updated: " + e.getMessage());
                orders.save(order);
            }));
        }
    }

    // ---- rules ----

    private static Next decide(OrderStatus orderStatus, String status) {
        String s = status.toLowerCase(Locale.ROOT);
        if (s.equals("delivered")) {
            if (orderStatus == OrderStatus.READY) {
                return Next.DISPATCH_THEN_DELIVER;
            }
            return orderStatus == OrderStatus.DISPATCHED ? Next.DELIVER : Next.NONE;
        }
        if ((s.equals("picked_up") || s.equals("in_transit")) && orderStatus == OrderStatus.READY) {
            return Next.DISPATCH;
        }
        return Next.NONE;
    }

    /** How far along a delivery is; unknown statuses count as "assigned" so they are recorded but never block a later one. */
    static int progress(String status) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "created", "pending" -> 0;
            case "picked_up" -> 2;
            case "in_transit" -> 3;
            case "delivered" -> 4;
            default -> 1;
        };
    }

    /**
     * A late "assigned" after "in_transit" must not undo it. Delivered is final (the order has been marked
     * delivered and stock posted; a stray "cancelled" after it is noise, not news), and a delivery that
     * failed or was cancelled stays that way unless an end state arrives — a late "in_transit" must not make
     * staff think a dead delivery is alive, but a "delivered" after a "failed" means the parcel did arrive.
     */
    static boolean isOlderThanRecorded(String recorded, String incoming) {
        if (recorded == null) {
            return false;
        }
        boolean incomingEnds = TumaBodaBookingService.isDeadStatus(incoming) || incoming.equalsIgnoreCase("delivered");
        if (recorded.equalsIgnoreCase("delivered")) {
            return !incoming.equalsIgnoreCase("delivered");
        }
        if (TumaBodaBookingService.isDeadStatus(recorded)) {
            return !incomingEnds;
        }
        return !TumaBodaBookingService.isDeadStatus(incoming) && progress(incoming) < progress(recorded);
    }
}
