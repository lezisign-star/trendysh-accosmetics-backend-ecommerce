package com.nbh.backend.common.event;

import java.util.UUID;

/** Published by {@code OrderFulfilmentService.cancel} after a staff-initiated cancellation. */
public record OrderCancelledEvent(UUID orderId, String orderNo, String reason) {}
