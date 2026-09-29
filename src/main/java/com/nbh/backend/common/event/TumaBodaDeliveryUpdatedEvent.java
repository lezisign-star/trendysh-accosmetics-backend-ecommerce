package com.nbh.backend.common.event;

import java.util.UUID;

/**
 * Published when TumaBoda's side of an order changes: a rider was booked, a booking failed, or TumaBoda
 * reported progress. {@code status} is TumaBoda's own word for it ({@code in_transit}, {@code delivered}, ...)
 * or, for a booking that did not go through, {@code booking_failed}.
 */
public record TumaBodaDeliveryUpdatedEvent(UUID orderId, String orderNo, String status) {}
