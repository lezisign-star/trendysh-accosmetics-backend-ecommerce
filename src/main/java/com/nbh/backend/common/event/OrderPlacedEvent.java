package com.nbh.backend.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published by {@code CheckoutService.checkout} after a guest's cart becomes
 * a PENDING_PAYMENT order. IDs and scalars only — never an entity — so an
 * AFTER_COMMIT listener never touches a lazy field from a detached proxy
 * (open-in-view is false; see {@code EventLoggingListener}'s javadoc).
 */
public record OrderPlacedEvent(
        UUID orderId,
        String orderNo,
        String guestEmail,
        String guestPhone,
        BigDecimal grandTotal,
        int itemCount
) {}
