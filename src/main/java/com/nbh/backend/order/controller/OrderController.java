package com.nbh.backend.order.controller;

import com.nbh.backend.common.util.PhoneNumbers;
import com.nbh.backend.order.dto.AdminOrderDetailDto;
import com.nbh.backend.order.dto.AssignTransporterRequest;
import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.order.dto.OrderReturnRequest;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.order.dto.OrderSummaryDto;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.order.service.OrderFulfilmentService;
import com.nbh.backend.order.service.OrderReturnService;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final CustomerOrderRepository orderRepository;
    private final OrderFulfilmentService fulfilmentService;
    private final OrderReturnService returnService;
    private final PaymentRecordRepository paymentRecordRepository;

    public OrderController(CustomerOrderRepository orderRepository, OrderFulfilmentService fulfilmentService,
                            OrderReturnService returnService, PaymentRecordRepository paymentRecordRepository) {
        this.orderRepository = orderRepository;
        this.fulfilmentService = fulfilmentService;
        this.returnService = returnService;
        this.paymentRecordRepository = paymentRecordRepository;
    }

    /**
     * Staff order browse — paginated, optional status filter and free-text
     * search (order number, email, or phone — {@code CustomerOrder} doesn't
     * capture a customer name today, so name search isn't possible yet; see
     * the integration readiness plan for that gap). {@code Transactional}
     * not needed here — {@code CustomerOrderRepository.search} returns an
     * already-built DTO projection, no lazy access happens in this method.
     */
    @PreAuthorize("hasAuthority('PERM_ORDER_VIEW') or hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @GetMapping
    public Page<OrderSummaryDto> list(@RequestParam(required = false) OrderStatus status,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "false") boolean needsAttentionOnly,
                                       @RequestParam(defaultValue = "false") boolean queue,
                                       @PageableDefault(size = 20) Pageable pageable) {
        String term = (q == null || q.isBlank()) ? null : q.trim();
        String phone = PhoneNumbers.searchFragment(term);
        Instant now = Instant.now();
        if (queue) {
            // The to-do list: only orders still waiting on staff, longest-waiting first. An explicit
            // status narrows it further (e.g. just PAID), but never widens it to handled orders.
            Collection<OrderStatus> open = status == null ? QUEUE_STATUSES
                    : QUEUE_STATUSES.contains(status) ? List.of(status) : List.of();
            if (open.isEmpty()) return Page.empty(pageable);
            return orderRepository.searchQueue(open, term, phone, needsAttentionOnly, pageable)
                    .map(o -> o.withUrgency(now));
        }
        return orderRepository.search(status, term, phone, needsAttentionOnly, pageable)
                .map(o -> o.withUrgency(now));
    }

    /**
     * Orders still waiting on staff. Dispatched and delivered orders are handled, so they drop out of the
     * queue (as do cancelled, failed and expired ones) — delivered is reached either by staff or by the
     * customer confirming on the track page, or automatically for TumaBoda deliveries.
     */
    static final List<OrderStatus> QUEUE_STATUSES = List.of(
            OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.PICKING, OrderStatus.READY);

    /** The Fulfilment tab's "Needs attention (N)" chip count — see {@code CustomerOrderRepository.countNeedingAttention}. */
    @PreAuthorize("hasAuthority('PERM_ORDER_VIEW') or hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @GetMapping("/needs-attention-count")
    public NeedsAttentionCount needsAttentionCount() {
        return new NeedsAttentionCount(orderRepository.countNeedingAttention());
    }

    public record NeedsAttentionCount(long count) {}

    // Guest tracking (order number alone = status only; email = emailed code, then its orders) lives in
    // OrderTrackingController. It used to be here, returning a full order for a number plus email.

    /** Staff detail view — see {@link AdminOrderDetailDto} for why this isn't the shared {@link OrderDto}. */
    @PreAuthorize("hasAuthority('PERM_ORDER_VIEW') or hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public AdminOrderDetailDto get(@PathVariable java.util.UUID id) {
        CustomerOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + id));
        return AdminOrderDetailDto.from(order, paymentRecordRepository.findByOrderId(id));
    }

    /** Who's actually carrying a DELIVERY_COURIER order, and the price agreed by phone. See {@code OrderFulfilmentService.assignTransporter}. */
    @PreAuthorize("hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{id}/assign-transporter")
    @Transactional
    public OrderDto assignTransporter(@PathVariable java.util.UUID id, @Valid @RequestBody AssignTransporterRequest request) {
        return OrderDto.from(fulfilmentService.assignTransporter(id, request));
    }

    /**
     * Posts the real stock movement and closes the order's reservations — see
     * OrderFulfilmentService's javadoc. @Transactional here (not just on the
     * service method) so OrderDto.from's lazy variant/branch access below
     * joins the SAME Hibernate session the service method used, rather than
     * hitting a closed one — the exact class of bug session 3's audit found
     * in StockController.balance().
     */
    @PreAuthorize("hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{id}/fulfil")
    @Transactional
    public OrderDto fulfil(@PathVariable java.util.UUID id, @AuthenticationPrincipal StaffUser staff) {
        return OrderDto.from(fulfilmentService.fulfil(id, staff.getId()));
    }

    /** PAID -> PICKING. See {@code OrderFulfilmentService.startPicking}. */
    @PreAuthorize("hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{id}/start-picking")
    @Transactional
    public OrderDto startPicking(@PathVariable java.util.UUID id) {
        return OrderDto.from(fulfilmentService.startPicking(id));
    }

    /** PAID or PICKING -> READY. See {@code OrderFulfilmentService.markReady}. */
    @PreAuthorize("hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{id}/mark-ready")
    @Transactional
    public OrderDto markReady(@PathVariable java.util.UUID id) {
        return OrderDto.from(fulfilmentService.markReady(id));
    }

    /** DISPATCHED -> DELIVERED — the step that was missing entirely before 2026-09-22. See {@code OrderFulfilmentService.markDelivered}. */
    @PreAuthorize("hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{id}/mark-delivered")
    @Transactional
    public OrderDto markDelivered(@PathVariable java.util.UUID id) {
        return OrderDto.from(fulfilmentService.markDelivered(id));
    }

    /** See {@code OrderFulfilmentService.cancel}'s javadoc for exactly which statuses this accepts. */
    @PreAuthorize("hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{id}/cancel")
    @Transactional
    public OrderDto cancel(@PathVariable java.util.UUID id, @RequestBody(required = false) CancelOrderRequest request) {
        String reason = request != null ? request.reason() : null;
        return OrderDto.from(fulfilmentService.cancel(id, reason));
    }

    /**
     * Records a customer return against a DISPATCHED/DELIVERED order — see
     * {@code OrderReturnService} for the reversal-at-original-cost mechanics. Same permission
     * as the other post-dispatch order actions; {@code @Transactional} so the response DTO's
     * lazy item access below shares the service call's session.
     */
    @PreAuthorize("hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{id}/returns")
    @Transactional
    public AdminOrderDetailDto postReturn(@PathVariable java.util.UUID id, @jakarta.validation.Valid @RequestBody OrderReturnRequest request) {
        CustomerOrder order = returnService.post(id, request);
        return AdminOrderDetailDto.from(order, paymentRecordRepository.findByOrderId(id));
    }

    public record CancelOrderRequest(String reason) {}
}
