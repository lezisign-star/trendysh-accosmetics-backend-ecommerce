package com.nbh.backend.common.event;

import java.util.UUID;

/**
 * Published by {@code OrderFulfilmentService.setPacked} when staff tick or untick products on an order's pick-and-pack
 * list, so another member of staff with the same order open sees the tick. Staff only: customers are not told.
 */
public record OrderPackedEvent(UUID orderId, String orderNo) {}
