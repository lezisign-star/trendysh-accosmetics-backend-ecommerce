package com.nbh.backend.order.service;

import com.nbh.backend.branch.entity.LocationType;
import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.branch.repository.StockLocationRepository;
import com.nbh.backend.common.event.OrderCancelledEvent;
import com.nbh.backend.common.event.OrderDeliveredEvent;
import com.nbh.backend.common.event.OrderFulfilledEvent;
import com.nbh.backend.common.event.OrderPickingStartedEvent;
import com.nbh.backend.common.event.OrderReadyEvent;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.service.StockDocumentNoGenerator;
import com.nbh.backend.stock.service.StockPostingService;
import com.nbh.backend.stock.service.StockReservationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * The step that actually closes the loop this whole platform was built for:
 * turning a PAID order into a real stock movement. One SALE_ONLINE document,
 * one line per order item, each line naming the reservation it fulfils — so
 * StockPostingService closes the hold (decrements reserved_qty, marks the
 * reservation FULFILLED) in the SAME transaction as the ledger append (audit
 * finding F3, session 3). No separate call to StockReservationService is
 * needed or correct here; going through the document line is what makes this
 * atomic.
 *
 * {@code fulfil} still does the one real stock movement (PAID/PICKING/READY
 * -> DISPATCHED) — that's unchanged and deliberate, since nothing physically
 * leaves the shelf until a rider/vehicle/customer actually takes it. What's
 * no longer collapsed is the staff-visible/customer-visible journey leading
 * up to that: {@link #startPicking} and {@link #markReady} are real,
 * independently settable status transitions (a small operation can still
 * skip straight from PAID to {@code fulfil} if it doesn't want the extra
 * granularity — {@code fulfil}'s precondition accepts PAID, PICKING or READY
 * for exactly that reason), and {@link #markDelivered} closes the loop that
 * was previously missing entirely: nothing ever moved an order past
 * DISPATCHED, so the customer-facing tracker's "Delivered" step was
 * unreachable. See HANDOFF.md's note on this (found 2026-09-22).
 */
@Service
public class OrderFulfilmentService {

    /** PICKING and READY have no stock movement yet (that only happens in {@link #fulfil}), so a PAID order can start picking at any point before dispatch. */
    private static final Set<OrderStatus> PICKABLE_STATUSES = EnumSet.of(OrderStatus.PAID);

    /** Ready can follow either PAID directly (skip the picking stage) or PICKING. */
    private static final Set<OrderStatus> READYABLE_STATUSES =
            EnumSet.of(OrderStatus.PAID, OrderStatus.PICKING);

    /** {@code fulfil} (the real stock movement) can be triggered from any pre-dispatch stage — staff aren't forced through PICKING/READY if they don't use them. */
    private static final Set<OrderStatus> DISPATCHABLE_STATUSES =
            EnumSet.of(OrderStatus.PAID, OrderStatus.PICKING, OrderStatus.READY);

    /**
     * Anything DISPATCHED or later has physically left the building —
     * cancel is a pre-dispatch escape hatch. PICKING/READY are included
     * (not just PENDING_PAYMENT/PAID) because no stock movement has
     * happened yet at those stages either — the reservation is still just
     * a hold, not a posted sale, until {@link #fulfil} runs.
     */
    private static final Set<OrderStatus> CANCELLABLE_STATUSES =
            EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.PICKING, OrderStatus.READY);

    private final CustomerOrderRepository orderRepository;
    private final StockLocationRepository locationRepository;
    private final StockBalanceRepository balanceRepository;
    private final StockPostingService postingService;
    private final StockDocumentNoGenerator documentNoGenerator;
    private final StockReservationService reservationService;
    private final ApplicationEventPublisher eventPublisher;

    public OrderFulfilmentService(CustomerOrderRepository orderRepository,
                                   StockLocationRepository locationRepository,
                                   StockBalanceRepository balanceRepository,
                                   StockPostingService postingService,
                                   StockDocumentNoGenerator documentNoGenerator,
                                   StockReservationService reservationService,
                                   ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.locationRepository = locationRepository;
        this.balanceRepository = balanceRepository;
        this.postingService = postingService;
        this.documentNoGenerator = documentNoGenerator;
        this.reservationService = reservationService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public CustomerOrder fulfil(java.util.UUID orderId, java.util.UUID staffUserId) {
        CustomerOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
        if (!DISPATCHABLE_STATUSES.contains(order.getStatus())) {
            throw new UnprocessableException(
                    "Order " + order.getOrderNo() + " is " + order.getStatus() + " — cannot fulfil it");
        }

        StockLocation salesFloor = locationRepository
                .findByBranchIdAndLocationType(order.getFulfilmentBranch().getId(), LocationType.SALES_FLOOR)
                .orElseThrow();
        StockLocation customer = locationRepository.findByCode("CUSTOMER").orElseThrow();

        StockDocument document = new StockDocument();
        document.setDocumentNo(documentNoGenerator.next(StockDocumentType.SALE_ONLINE));
        document.setDocumentType(StockDocumentType.SALE_ONLINE);
        document.setBranch(order.getFulfilmentBranch());
        document.setReferenceType("CUSTOMER_ORDER");
        document.setReferenceId(order.getId());
        document.setPostedByUserId(staffUserId);

        int lineNo = 1;
        for (CustomerOrderItem item : order.getItems()) {
            // Snapshot the current average cost BEFORE posting — an outbound
            // movement doesn't change it, so this is exactly the figure
            // StockPostingService will use as the ledger entry's unit_cost.
            balanceRepository.findByVariantIdAndLocationId(item.getVariant().getId(), salesFloor.getId())
                    .ifPresent(b -> item.setUnitCostAtSale(b.getAvgCost()));

            StockDocumentLine line = new StockDocumentLine();
            line.setDocument(document);
            line.setLineNo(lineNo++);
            line.setVariant(item.getVariant());
            line.setSourceLocation(salesFloor);
            line.setTargetLocation(customer);
            line.setQty(item.getQty());
            line.setUnitPrice(item.getUnitPrice());
            line.setReservation(item.getReservation());
            document.getLines().add(line);
        }

        postingService.post(document);

        order.setStatus(OrderStatus.DISPATCHED);
        order = orderRepository.save(order);

        // §2: the designated seam for a staff/courier notification — see
        // OrderFulfilledEvent's javadoc. Published here (inside this
        // @Transactional method), delivered
        // AFTER_COMMIT: postingService.post above can still throw
        // InsufficientStockException if stock moved underneath, which would have
        // already rolled back everything before this line ever ran.
        eventPublisher.publishEvent(new OrderFulfilledEvent(
                order.getId(), order.getOrderNo(), order.getFulfilmentBranch().getId(),
                order.getFulfilmentType(), order.getDeliveryAddress()));

        return order;
    }

    /** PAID -> PICKING. No stock movement — see {@link #PICKABLE_STATUSES}. */
    @Transactional
    public CustomerOrder startPicking(java.util.UUID orderId) {
        CustomerOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
        if (!PICKABLE_STATUSES.contains(order.getStatus())) {
            throw new UnprocessableException(
                    "Order " + order.getOrderNo() + " is " + order.getStatus() + " — cannot start picking");
        }

        order.setStatus(OrderStatus.PICKING);
        order = orderRepository.save(order);

        eventPublisher.publishEvent(new OrderPickingStartedEvent(order.getId(), order.getOrderNo()));

        return order;
    }

    /** PAID or PICKING -> READY. No stock movement — see {@link #READYABLE_STATUSES}. */
    @Transactional
    public CustomerOrder markReady(java.util.UUID orderId) {
        CustomerOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
        if (!READYABLE_STATUSES.contains(order.getStatus())) {
            throw new UnprocessableException(
                    "Order " + order.getOrderNo() + " is " + order.getStatus() + " — cannot mark it ready");
        }

        order.setStatus(OrderStatus.READY);
        order = orderRepository.save(order);

        eventPublisher.publishEvent(
                new OrderReadyEvent(order.getId(), order.getOrderNo(), order.getFulfilmentType()));

        return order;
    }

    /**
     * DISPATCHED -> DELIVERED. This is the step that was missing entirely
     * before 2026-09-22 — nothing ever set DELIVERED, so the customer-facing
     * tracker's final step was unreachable no matter how long an order sat
     * dispatched. A manual staff action (confirmed by phone/WhatsApp with the
     * rider or the customer, per the founder's manual-delivery model — there
     * is no automated courier webhook to trigger this instead).
     */
    @Transactional
    public CustomerOrder markDelivered(java.util.UUID orderId) {
        CustomerOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
        if (order.getStatus() != OrderStatus.DISPATCHED) {
            throw new UnprocessableException(
                    "Order " + order.getOrderNo() + " is " + order.getStatus() + " — cannot mark it delivered (must be DISPATCHED first)");
        }

        order.setStatus(OrderStatus.DELIVERED);
        order = orderRepository.save(order);

        eventPublisher.publishEvent(new OrderDeliveredEvent(order.getId(), order.getOrderNo()));

        return order;
    }

    /**
     * A staff-initiated cancellation — the frontend's admin orders screen
     * assumes this action exists; it didn't until now. Deliberately narrower
     * than a general "set status to X" endpoint: only pre-dispatch orders
     * (PENDING_PAYMENT/PAID/PICKING/READY) are cancellable (releases any
     * active reservation back to available stock); anything DISPATCHED or
     * later has physically left the building and needs a real return/refund
     * process, not a status flip.
     */
    @Transactional
    public CustomerOrder cancel(java.util.UUID orderId, String reason) {
        CustomerOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
        if (!CANCELLABLE_STATUSES.contains(order.getStatus())) {
            throw new UnprocessableException(
                    "Order " + order.getOrderNo() + " is " + order.getStatus() + " — cannot be cancelled");
        }

        for (CustomerOrderItem item : order.getItems()) {
            if (item.getReservation() != null
                    && item.getReservation().getStatus() == StockReservation.ReservationStatus.ACTIVE) {
                reservationService.release(item.getReservation().getId());
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        order = orderRepository.save(order);

        eventPublisher.publishEvent(new OrderCancelledEvent(order.getId(), order.getOrderNo(), reason));

        return order;
    }
}
