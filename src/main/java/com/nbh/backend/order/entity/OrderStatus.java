package com.nbh.backend.order.entity;

/**
 * PENDING_PAYMENT -> PAID -> PICKING -> READY -> DISPATCHED -> DELIVERED is
 * the happy path (adapted from Moments Packaging's OrderStatus, trimmed —
 * no PAYMENT_VERIFIED stage since Daraja confirmation IS the verification
 * here, no separate manual step yet). CANCELLED/PAYMENT_FAILED/EXPIRED are
 * terminal off-ramps.
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    PICKING,
    READY,
    DISPATCHED,
    DELIVERED,
    CANCELLED,
    PAYMENT_FAILED,
    EXPIRED
}
