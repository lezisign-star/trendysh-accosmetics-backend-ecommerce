package com.nbh.backend.common.event;

import com.nbh.backend.order.entity.FulfilmentType;

import java.util.UUID;

/**
 * Published by {@code OrderFulfilmentService.markReady} when an order moves to READY.
 * Carries {@code fulfilmentType} so a future listener (e.g. "your pickup is ready" vs
 * "your rider is being arranged" notification) can branch without a second DB lookup —
 * same reasoning as {@link OrderFulfilledEvent}.
 */
public record OrderReadyEvent(UUID orderId, String orderNo, FulfilmentType fulfilmentType) {}
