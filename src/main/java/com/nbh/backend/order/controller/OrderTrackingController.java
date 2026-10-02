package com.nbh.backend.order.controller;

import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.order.dto.TrackingDtos.CodeRequest;
import com.nbh.backend.order.dto.TrackingDtos.OrderStatusDto;
import com.nbh.backend.order.dto.TrackingDtos.SessionDto;
import com.nbh.backend.order.dto.TrackingDtos.TrackedOrderSummaryDto;
import com.nbh.backend.order.dto.TrackingDtos.VerifyRequest;
import com.nbh.backend.order.service.OrderTrackingService;
import com.nbh.backend.live.LiveStreams;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final LiveStreams streams;

    public OrderTrackingController(OrderTrackingService tracking, LiveStreams streams) {
        this.tracking = tracking;
        this.streams = streams;
    }

    @GetMapping("/{orderNo}")
    public OrderStatusDto status(@PathVariable String orderNo) {
        return tracking.status(orderNo);
    }

    /**
     * Tells the pay/track page THAT this order changed — never what changed — so it knows to ask the real
     * endpoint again right away instead of on its next poll. An order number that does not exist is a 404,
     * same as {@link #status}; nothing here needs an email or a track token, so it stays as anonymous as
     * the order number itself already is.
     */
    @GetMapping(value = "/{orderNo}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable String orderNo) {
        return streams.subscribeOrder(tracking.verifiedOrderNo(orderNo));
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

    /** The customer says the dispatched order has arrived — closes it (DELIVERED) and unlocks reviews. */
    @PostMapping("/email/orders/{orderNo}/confirm-received")
    public OrderDto confirmReceived(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                    @PathVariable String orderNo) {
        return tracking.confirmReceived(token, orderNo);
    }

    /** Rate one product from a delivered order. Reviews wait for staff approval before they appear in the shop. */
    @PostMapping("/email/orders/{orderNo}/reviews")
    public Map<String, String> review(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                      @PathVariable String orderNo,
                                      @Valid @RequestBody com.nbh.backend.order.dto.TrackingDtos.TrackedReviewRequest request) {
        tracking.review(token, orderNo, request);
        return Map.of("message", "Thanks — your review will show once our team has read it.");
    }

    @GetMapping("/email/orders/{orderNo}")
    public OrderDto order(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                          @PathVariable String orderNo) {
        return tracking.order(token, orderNo);
    }
}
