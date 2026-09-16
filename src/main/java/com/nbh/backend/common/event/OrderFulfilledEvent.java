package com.nbh.backend.common.event;

import com.nbh.backend.order.entity.FulfilmentType;

import java.util.UUID;

/**
 * Published by {@code OrderFulfilmentService.fulfil} once the SALE_ONLINE
 * document is posted and the order moves to DISPATCHED. The designated
 * TumaBoda dispatch seam (deliberately not built yet — see HANDOFF.md) — a
 * future courier integration is a new listener on this event, not a change
 * to {@code OrderFulfilmentService} itself.
 */
public record OrderFulfilledEvent(
        UUID orderId,
        String orderNo,
        UUID branchId,
        FulfilmentType fulfilmentType,
        String deliveryAddress
) {}
