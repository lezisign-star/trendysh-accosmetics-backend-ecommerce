package com.nbh.backend.customer.controller;

import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.customer.dto.CustomerDtos.MyOrderDto;
import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** A signed-in shopper's own orders — never anyone else's. */
@RestController
@RequestMapping("/api/v1/customer/orders")
public class CustomerOrderController {

    private final CustomerOrderRepository orderRepository;

    public CustomerOrderController(CustomerOrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @GetMapping
    public List<MyOrderDto> mine(@AuthenticationPrincipal CustomerAccount customer) {
        return orderRepository.findMine(customer.getId());
    }

    @GetMapping("/{orderNo}")
    @Transactional(readOnly = true)
    public OrderDto detail(@AuthenticationPrincipal CustomerAccount customer, @PathVariable String orderNo) {
        // An order that exists but belongs to someone else is reported exactly like one that doesn't exist.
        return orderRepository.findByOrderNo(orderNo)
                .filter(o -> o.getCustomerAccount() != null && o.getCustomerAccount().getId().equals(customer.getId()))
                .map(OrderDto::from)
                .orElseThrow(() -> new NotFoundException("No order " + orderNo + " on your account"));
    }
}
