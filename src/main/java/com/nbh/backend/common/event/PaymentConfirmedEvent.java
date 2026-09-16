package com.nbh.backend.common.event;

import com.nbh.backend.payment.entity.PaymentRecord;

import java.math.BigDecimal;
import java.util.UUID;

/** Published by {@code PaymentService.confirmSuccess} after the order flips to PAID. */
public record PaymentConfirmedEvent(
        UUID paymentRecordId,
        UUID orderId,
        String orderNo,
        BigDecimal amount,
        PaymentRecord.PaymentMethod method,
        String providerReference
) {}
