package com.nbh.backend.order.controller;

import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.order.dto.TrackingDtos.CodeRequest;
import com.nbh.backend.order.dto.TrackingDtos.OrderStatusDto;
import com.nbh.backend.order.dto.TrackingDtos.SessionDto;
import com.nbh.backend.order.dto.TrackingDtos.TrackedOrderSummaryDto;
import com.nbh.backend.order.dto.TrackingDtos.VerifyRequest;
import com.nbh.backend.order.service.OrderTrackingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public guest order lookup — see {@link OrderTrackingService}. Two separate ways in: an order
 * number alone (status only), or an email proven with an emailed code (its orders, then details).
 */
@RestController
@RequestMapping("/api/v1/orders/track")
public class OrderTrackingController {

    static final String TOKEN_HEADER = "X-Track-Token";

    private final OrderTrackingService tracking;

    public OrderTrackingController(OrderTrackingService tracking) {
        this.tracking = tracking;
    }

    @GetMapping("/{orderNo}")
    public OrderStatusDto status(@PathVariable String orderNo) {
        return tracking.status(orderNo);
    }

    @PostMapping("/email/code")
    public Map<String, String> requestCode(@Valid @RequestBody CodeRequest request) {
        tracking.requestCode(request.email());
        return Map.of("message", "If there are orders under that email, we've sent it a 6-digit code.");
    }

    @PostMapping("/email/verify")
    public SessionDto verify(@Valid @RequestBody VerifyRequest request) {
        return tracking.verify(request.email(), request.code());
    }

    @GetMapping("/email/orders")
    public List<TrackedOrderSummaryDto> orders(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                               @RequestParam(required = false) String orderNo) {
        return tracking.orders(token, orderNo);
    }

    @GetMapping("/email/orders/{orderNo}")
    public OrderDto order(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                          @PathVariable String orderNo) {
        return tracking.order(token, orderNo);
    }
}
