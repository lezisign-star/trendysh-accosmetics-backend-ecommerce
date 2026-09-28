package com.nbh.backend.order.service;

import com.nbh.backend.common.event.OrderCancelledEvent;
import com.nbh.backend.common.event.OrderDeliveredEvent;
import com.nbh.backend.common.event.OrderFulfilledEvent;
import com.nbh.backend.common.event.OrderPickingStartedEvent;
import com.nbh.backend.common.event.OrderReadyEvent;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.common.util.PhoneNumbers;
import com.nbh.backend.order.dto.AssignTransporterRequest;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.service.StockAvailabilityService;
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
 * <p>Before branches were removed (2026-09-24, founder decision — see
 * HANDOFF.md), this method looked up a "sales floor" location keyed by the
 * order's fulfilment branch — which was NOT necessarily the branch
 * {@code StockReservationService.reserve} actually reserved from, since
 * checkout's branch-fallback logic could reserve at a different branch than
 * the order's nominal fulfilment branch. That mismatch is now structurally
 * impossible: a reservation only ever holds SHOP stock (enforced in
 * {@code StockPostingService.post}), and dispatch always sources from SHOP —
 * there is nowhere else it could come from.
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
    private final StockAvailabilityService availabilityService;
    private final StockPostingService postingService;
    private final StockDocumentNoGenerator documentNoGenerator;
    private final StockReservationService reservationService;
    private final ApplicationEventPublisher eventPublisher;

    public OrderFulfilmentService(CustomerOrderRepository orderRepository,
                                   StockAvailabilityService availabilityService,
                                   StockPostingService postingService,
                                   StockDocumentNoGenerator documentNoGenerator,
                                   StockReservationService reservationService,
                                   ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.availabilityService = availabilityService;
        this.postingService = postingService;
        this.documentNoGenerator = documentNoGenerator;
        this.reservationService = reservationService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Records who was actually assigned to carry a DELIVERY_COURIER order and what
     * was agreed on the phone (V35) — settable any time before dispatch, and
     * re-settable (a rider falling through and a replacement being called is a real
     * scenario) right up until {@link #fulfil} runs. {@link #fulfil} refuses to move
     * a courier order to DISPATCHED without this, which is the whole point: before
     * this existed, the call happened but nothing about it was ever recorded.
     */
    @Transactional
    public CustomerOrder assignTransporter(java.util.UUID orderId, AssignTransporterRequest request) {
        CustomerOrder order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
        if (order.getFulfilmentType() != FulfilmentType.DELIVERY_COURIER) {
            throw new UnprocessableException(
                    "Order " + order.getOrderNo() + " is a pickup order — there is no transporter to assign");
        }
        if (!DISPATCHABLE_STATUSES.contains(order.getStatus())) {
            throw new UnprocessableException(
                    "Order " + order.getOrderNo() + " is " + order.getStatus() + " — cannot assign a transporter");
        }

        order.setTransporterName(request.transporterName().trim());
        order.setTransporterPhone(PhoneNumbers.normalize(request.transporterPhone()));
        order.setAgreedDeliveryPrice(request.agreedPrice());
        return orderRepository.save(order);
    }

    @Transactional
    public CustomerOrder fulfil(java.util.UUID orderId, java.util.UUID staffUserId) {
        // Phase 0 fix (2026-09-27), D2 — see CustomerOrderRepository.lockById's javadoc.
        CustomerOrder order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
        if (!DISPATCHABLE_STATUSES.contains(order.getStatus())) {
            throw new UnprocessableException(
                    "Order " + order.getOrderNo() + " is " + order.getStatus() + " — cannot fulfil it");
        }
        if (order.getFulfilmentType() == FulfilmentType.DELIVERY_COURIER && order.getTransporterName() == null) {
            throw new UnprocessableException(
                    "Assign a rider, matatu or courier for " + order.getOrderNo() + " before marking it dispatched");
        }

        StockDocument document = new StockDocument();
        document.setDocumentNo(documentNoGenerator.next(StockDocumentType.SALE_ONLINE));
        document.setDocumentType(StockDocumentType.SALE_ONLINE);
        document.setReferenceType("CUSTOMER_ORDER");
        document.setReferenceId(order.getId());
        document.setPostedByUserId(staffUserId);

        int lineNo = 1;
        java.util.List<CustomerOrderItem> itemsInLineOrder = new java.util.ArrayList<>();
        for (CustomerOrderItem item : order.getItems()) {
            StockDocumentLine line = new StockDocumentLine();
            line.setDocument(document);
            line.setLineNo(lineNo++);
            line.setVariant(item.getVariant());
            line.setSourceAccount(StockAccount.SHOP);
            line.setTargetAccount(StockAccount.CUSTOMER);
            line.setQty(item.getQty());
            line.setUnitPrice(item.getUnitPrice());
            line.setReservation(item.getReservation());
            document.getLines().add(line);
            itemsInLineOrder.add(item);
        }

        postingService.post(document);

        // Phase 0 fix (2026-09-27): the COGS snapshot now comes from what posting actually
        // locked and used (StockDocumentLine.postedUnitCost), not a pre-posting avgCost()
        // read — that read could already be stale by the time posting takes the lock and
        // commits, so the snapshot could silently disagree with what the ledger recorded.
        // document.getLines() and itemsInLineOrder were built in lockstep above, so they're
        // index-aligned.
        for (int i = 0; i < document.getLines().size(); i++) {
            itemsInLineOrder.get(i).setUnitCostAtSale(document.getLines().get(i).getPostedUnitCost());
        }

        order.setStatus(OrderStatus.DISPATCHED);
        order = orderRepository.save(order);

        // §2: the designated seam for a staff/courier notification — see
        // OrderFulfilledEvent's javadoc. Published here (inside this
        // @Transactional method), delivered
        // AFTER_COMMIT: postingService.post above can still throw
        // InsufficientStockException if stock moved underneath, which would have
        // already rolled back everything before this line ever ran.
        eventPublisher.publishEvent(new OrderFulfilledEvent(
                order.getId(), order.getOrderNo(), order.getFulfilmentType(), order.getDeliveryAddress()));

        return order;
    }

    /** PAID -> PICKING. No stock movement — see {@link #PICKABLE_STATUSES}. */
    @Transactional
    public CustomerOrder startPicking(java.util.UUID orderId) {
        // Phase 0 fix (2026-09-27), D2 — see CustomerOrderRepository.lockById's javadoc.
        CustomerOrder order = orderRepository.lockById(orderId)
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
        // Phase 0 fix (2026-09-27), D2 — see CustomerOrderRepository.lockById's javadoc.
        CustomerOrder order = orderRepository.lockById(orderId)
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
        // Phase 0 fix (2026-09-27), D2 — see CustomerOrderRepository.lockById's javadoc.
        CustomerOrder order = orderRepository.lockById(orderId)
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
        // Phase 0 fix (2026-09-27), D2 — see CustomerOrderRepository.lockById's javadoc.
        CustomerOrder order = orderRepository.lockById(orderId)
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
