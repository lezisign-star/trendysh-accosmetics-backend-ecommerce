package com.nbh.backend.common.event;

import java.util.UUID;

/** Published by {@code OrderFulfilmentService.startPicking} when a PAID order moves to PICKING. */
public record OrderPickingStartedEvent(UUID orderId, String orderNo) {}
