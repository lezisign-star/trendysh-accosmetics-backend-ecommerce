package com.nbh.backend.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Everything the customer's "pay now" page needs, and the poll target for
 * "has my payment arrived". {@code status} is derived for display:
 * PENDING (awaiting payment), PAID, FAILED, or EXPIRED (window passed or order
 * cancelled/expired). A null {@code paybill}/{@code bankTransfer} means that
 * option is not configured and its tab should be hidden.
 */
public record PaymentInstructionsDto(
        String orderNo,
        String reference,
        BigDecimal amount,
        String currency,
        String status,
        Instant expiresAt,
        Paybill paybill,
        BankTransfer bankTransfer,
        String supportWhatsapp,
        /** True once the customer has said "I've paid" and staff have not yet checked it. */
        boolean underReview
) {
    public record Paybill(String paybillNumber, String accountNumber) {}

    public record BankTransfer(String bankName, String accountName, String accountNumber, String branch,
                               String reference) {}
}
