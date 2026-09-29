package com.nbh.backend.common.event;

import java.util.UUID;

/** Published by {@code PaymentService.markFailed} — the symmetric counterpart to {@link PaymentConfirmedEvent}. */
public record PaymentFailedEvent(
        UUID paymentRecordId,
        UUID orderId,
        String orderNo,
        String reason
) {}
