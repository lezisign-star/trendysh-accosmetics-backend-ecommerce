package com.nbh.backend.order.dto;

import java.util.UUID;

/** {@code paymentInstructions} is set when payment is collected by bank, null for M-Pesa STK. */
public record CheckoutResponseDto(OrderDto order, UUID paymentRecordId,
                                  com.nbh.backend.payment.dto.PaymentInstructionsDto paymentInstructions) {}
