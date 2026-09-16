package com.nbh.backend.order.controller;

import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.order.service.OrderFulfilmentService;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final CustomerOrderRepository orderRepository;
    private final OrderFulfilmentService fulfilmentService;

    public OrderController(CustomerOrderRepository orderRepository, OrderFulfilmentService fulfilmentService) {
        this.orderRepository = orderRepository;
        this.fulfilmentService = fulfilmentService;
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

    @PreAuthorize("hasAuthority('PERM_ORDER_VIEW') or hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public OrderDto get(@PathVariable java.util.UUID id) {
        return orderRepository.findById(id)
                .map(OrderDto::from)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + id));
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
}
