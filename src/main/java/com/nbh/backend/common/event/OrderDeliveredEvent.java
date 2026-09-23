package com.nbh.backend.common.event;

import java.util.UUID;

/** Published by {@code OrderFulfilmentService.markDelivered} when a DISPATCHED order moves to DELIVERED. */
public record OrderDeliveredEvent(UUID orderId, String orderNo) {}
