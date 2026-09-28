package com.nbh.backend.tumaboda;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * What staff can do about an order's TumaBoda delivery from the order panel: book (or book again after a
 * failure), get a fresh rider after TumaBoda reported the delivery failed, switch the order back to a
 * phone-arranged transporter, or ask TumaBoda for the latest status right now. Each answers with the
 * booking outcome; the panel then re-reads the order.
 */
@RestController
@RequestMapping("/api/v1/orders/{id}/tumaboda")
@PreAuthorize("hasAuthority('PERM_ORDER_MANAGE_ALL')")
public class TumaBodaAdminController {

    private final TumaBodaBookingService bookings;
    private final TumaBodaStatusService statuses;

    public TumaBodaAdminController(TumaBodaBookingService bookings, TumaBodaStatusService statuses) {
        this.bookings = bookings;
        this.statuses = statuses;
    }

    @PostMapping("/book")
    public Map<String, String> book(@PathVariable UUID id) {
        return Map.of("outcome", bookings.retryNow(id).name());
    }

    @PostMapping("/restart")
    public Map<String, String> restart(@PathVariable UUID id) {
        return Map.of("outcome", bookings.restart(id).name());
    }

    @PostMapping("/reroute-manual")
    public ResponseEntity<Void> rerouteToManual(@PathVariable UUID id) {
        bookings.rerouteToManual(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/check")
    public ResponseEntity<Void> check(@PathVariable UUID id) {
        statuses.checkNow(id);
        return ResponseEntity.noContent().build();
    }
}
