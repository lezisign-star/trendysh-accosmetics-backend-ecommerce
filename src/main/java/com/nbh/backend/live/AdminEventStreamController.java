package com.nbh.backend.live;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live updates for staff dashboards — new orders, payments, claims, bank credits and delivery progress —
 * as a signal to reload, never the data itself (see {@link LiveStreams}). The browser side sends this
 * request with {@code fetch}/{@code ReadableStream}, not {@code EventSource}: only that lets it attach the
 * staff bearer token, and {@code SecurityFilterChain}'s normal staff-auth rule already covers this path.
 */
@RestController
@RequestMapping("/api/v1/admin/events")
public class AdminEventStreamController {

    private final LiveStreams streams;

    public AdminEventStreamController(LiveStreams streams) {
        this.streams = streams;
    }

    @GetMapping(produces = "text/event-stream")
    @PreAuthorize("hasAnyAuthority('PERM_ORDER_VIEW','PERM_ORDER_MANAGE_ALL','PERM_PAYMENT_MANAGE','PERM_SETTINGS_MANAGE','PERM_PRODUCT_VIEW','PERM_STOCK_VIEW')")
    public SseEmitter events() {
        return streams.subscribeStaff();
    }
}
