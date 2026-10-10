package com.nbh.backend.order.controller;

import com.nbh.backend.order.service.OrderAlertService;
import com.nbh.backend.order.service.OrderAlertService.Waiting;
import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** The new-order ring: what is still ringing, and stopping it for everyone. See {@link OrderAlertService}. */
@RestController
@RequestMapping("/api/v1/order-alerts")
public class OrderAlertController {

    private final OrderAlertService alerts;

    public OrderAlertController(OrderAlertService alerts) {
        this.alerts = alerts;
    }

    public record StopRequest(Instant upTo) {}

    public record Stopped(int stopped) {}

    @PreAuthorize("hasAuthority('PERM_ORDER_VIEW') or hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @GetMapping
    public Waiting waiting() {
        return alerts.waiting();
    }

    /**
     * Any member of staff who can see orders may stop the ring: it is a "seen" mark, not a decision about the order,
     * and the person at the counter is often not the one who manages orders.
     */
    @PreAuthorize("hasAuthority('PERM_ORDER_VIEW') or hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/stop")
    public Stopped stop(@AuthenticationPrincipal StaffUser staff, @RequestBody(required = false) StopRequest body) {
        return new Stopped(alerts.acknowledge(body == null ? null : body.upTo(), staff.getEmail()));
    }
}
