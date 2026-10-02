package com.nbh.backend.order.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Which orders staff should hurry. In Nairobi time an order is urgent when it was placed before
 * today ("From yesterday") or overnight, from 8pm to 6am ("Placed overnight") — customers who order
 * late expect it first thing, so those wait in the queue with a red warning.
 */
public final class OrderUrgency {

    public static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final int NIGHT_STARTS_HOUR = 20;
    static final int NIGHT_ENDS_HOUR = 6;

    private OrderUrgency() {}

    /** The reason an order placed at {@code placedAt} is urgent at {@code now}, or null if it isn't. */
    public static String reason(Instant placedAt, Instant now) {
        if (placedAt == null) return null;
        ZonedDateTime placed = placedAt.atZone(NAIROBI);
        LocalDate today = now.atZone(NAIROBI).toLocalDate();
        if (placed.toLocalDate().isBefore(today)) return "From yesterday";
        int hour = placed.getHour();
        if (hour >= NIGHT_STARTS_HOUR || hour < NIGHT_ENDS_HOUR) return "Placed overnight";
        return null;
    }
}
