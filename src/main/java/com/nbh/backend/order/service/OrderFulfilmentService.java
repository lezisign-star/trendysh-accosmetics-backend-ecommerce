package com.nbh.backend.order.service;

import com.nbh.backend.branch.entity.LocationType;
import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.branch.repository.StockLocationRepository;
import com.nbh.backend.common.event.OrderCancelledEvent;
import com.nbh.backend.common.event.OrderFulfilledEvent;
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
 * Deliberately collapses PICKING/READY/DISPATCHED into a single "fulfil"
 * action for now — a staff member marks the whole order dispatched at once.
 * Splitting that into real per-stage transitions (with their own timestamps)
 * is a reasonable later refinement, not a correctness requirement.
 */
@Service
public class OrderFulfilmentService {

    /** Anything already DISPATCHED or later has physically left the building — cancel is a checkout-time-only escape hatch. */
    private static final Set<OrderStatus> CANCELLABLE_STATUSES =
            EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID);

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
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException(
                    "Order " + order.getOrderNo() + " is " + order.getStatus() + ", not PAID — cannot fulfil it");
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

    /**
     * A staff-initiated cancellation — the frontend's admin orders screen
     * assumes this action exists; it didn't until now. Deliberately narrower
     * than a general "set status to X" endpoint: only PENDING_PAYMENT and
     * PAID orders are cancellable (releases any active reservation back to
     * available stock); anything DISPATCHED or later has physically left the
     * building and needs a real return/refund process, not a status flip.
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
