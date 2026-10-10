package com.nbh.backend.order.service;

import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The new-order ring. An order rings in every open admin until a member of staff stops it, and stopping it stops it
 * for everyone: whether an order has been seen is stored on the order (V60), so it does not depend on which tab or
 * phone happened to be open. The ring itself is made in the browser; this only says what is still waiting.
 */
@Service
public class OrderAlertService {

    /** How many waiting orders a screen is told about by name; the count always covers all of them. */
    static final int LISTED = 20;

    private final CustomerOrderRepository orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OrderAlertService(CustomerOrderRepository orders, ApplicationEventPublisher events, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.clock = clock;
    }

    public record Alert(UUID id, String orderNo, BigDecimal grandTotal, String customerName, String phone,
                        String fulfilmentType, Instant createdAt) {
        static Alert from(CustomerOrder o) {
            return new Alert(o.getId(), o.getOrderNo(), o.getGrandTotal(), o.getCustomerName(), o.getGuestPhone(),
                    o.getFulfilmentType().name(), o.getCreatedAt());
        }
    }

    /**
     * @param count how many orders are still ringing
     * @param upTo  the arrival time of the newest one listed: handed back to {@link #acknowledge} so that an order
     *              arriving while someone is reading the list is not silenced by their tap
     */
    public record Waiting(long count, Instant upTo, List<Alert> orders) {}

    @Transactional(readOnly = true)
    public Waiting waiting() {
        long count = orders.countUnacknowledgedAlerts();
        if (count == 0) {
            return new Waiting(0, null, List.of());
        }
        List<CustomerOrder> oldest = orders.unacknowledgedAlerts(PageRequest.of(0, LISTED));
        // The oldest are listed, but the stop covers everything that has arrived by now.
        Instant upTo = Instant.now(clock);
        return new Waiting(count, upTo, oldest.stream().map(Alert::from).toList());
    }

    /** Stops the ring for every order that arrived up to {@code upTo} (now, when none is given). */
    @Transactional
    public int acknowledge(Instant upTo, String staffEmail) {
        Instant now = Instant.now(clock);
        Instant limit = upTo == null || upTo.isAfter(now) ? now : upTo;
        int stopped = orders.acknowledgeAlertsUpTo(limit, now, staffEmail);
        if (stopped > 0) {
            // Every other open admin goes quiet too.
            events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.ORDER_ALERTS));
        }
        return stopped;
    }
}
