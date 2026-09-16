package com.nbh.backend.order.controller;

import com.nbh.backend.order.dto.CheckoutRequest;
import com.nbh.backend.order.dto.CheckoutResponseDto;
import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.order.service.CheckoutService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public — guest checkout, scoped by the same X-Cart-Session header CartController uses. */
@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping
    @Transactional
    public CheckoutResponseDto checkout(@RequestHeader("X-Cart-Session") String sessionId,
                                         @Valid @RequestBody CheckoutRequest request) {
        CheckoutService.CheckoutResult result = checkoutService.checkout(sessionId, request);
        return new CheckoutResponseDto(OrderDto.from(result.order()), result.paymentRecord().getId());
    }
}
