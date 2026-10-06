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
        /** True once the customer has sent an M-PESA code and staff have not yet checked it. */
        boolean underReview,
        /**
         * NONE (no code sent yet, so the order is not complete), PENDING (a code is waiting for a check) or REJECTED
         * (every code sent was turned down, so another is needed). See {@link PaymentCodeStatus}.
         */
        String codeStatus,
        /** The code waiting for a check, so the page can show the customer what was received; null if none. */
        String submittedCode
) {
    /** {@code merchantName} is what the customer's phone shows for this paybill (empty when not set). */
    public record Paybill(String paybillNumber, String accountNumber, String merchantName) {}

    public record BankTransfer(String bankName, String accountName, String accountNumber, String branch,
                               String reference) {}
}
