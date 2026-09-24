package com.nbh.backend.common.event;

import com.nbh.backend.order.entity.FulfilmentType;

import java.util.UUID;

/**
 * Published by {@code OrderFulfilmentService.fulfil} once the SALE_ONLINE
 * document is posted and the order moves to DISPATCHED. The designated seam
 * for notifying staff to arrange a rider/vehicle and call the customer
 * (deliberately not built yet — see HANDOFF.md; there is no automated
 * dispatch API to integrate with — TumaBoda is no longer used, and any
 * courier is arranged manually by phone/WhatsApp per the founder's
 * confirmed delivery model) — that notification is a new listener on this
 * event, not a change to {@code OrderFulfilmentService} itself.
 */
public record OrderFulfilledEvent(
        UUID orderId,
        String orderNo,
        FulfilmentType fulfilmentType,
        String deliveryAddress
) {}
