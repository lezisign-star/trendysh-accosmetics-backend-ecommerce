package com.nbh.backend.order.controller;

import com.nbh.backend.common.util.PhoneNumbers;
import com.nbh.backend.order.dto.AdminOrderDetailDto;
import com.nbh.backend.order.dto.AssignTransporterRequest;
import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.order.dto.OrderSummaryDto;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.order.service.OrderFulfilmentService;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
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
    private final PaymentRecordRepository paymentRecordRepository;

    public OrderController(CustomerOrderRepository orderRepository, OrderFulfilmentService fulfilmentService,
                            PaymentRecordRepository paymentRecordRepository) {
        this.orderRepository = orderRepository;
        this.fulfilmentService = fulfilmentService;
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
                                       @PageableDefault(size = 20) Pageable pageable) {
        String term = (q == null || q.isBlank()) ? null : q.trim();
        return orderRepository.search(status, term, PhoneNumbers.searchFragment(term), pageable);
    }

    /**
     * Guest order tracking by reference + email — no login required (design
     * doc §2.1: "guest tracking by reference"). Deliberately requires BOTH the
     * order number and the email it was placed under, so a guessed order
     * number alone can't leak another customer's order.
     */
    @GetMapping("/track/{orderNo}")
    @Transactional(readOnly = true)
    public OrderDto track(@PathVariable String orderNo, @RequestParam String email) {
        CustomerOrder order = orderRepository.findByOrderNo(orderNo)
                .filter(o -> o.getGuestEmail().equalsIgnoreCase(email))
                .orElseThrow(() -> new EntityNotFoundException("No order found for that reference and email"));
        return OrderDto.from(order);
    }

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

    public record CancelOrderRequest(String reason) {}
}
