package com.nbh.backend.payment.dto;

import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.entity.PaymentRecord;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Request/response shapes for the customer claim endpoint and the staff payments screen. */
public final class AdminPaymentDtos {

    private AdminPaymentDtos() {}

    // ---- customer -> the M-PESA code that completes an order: its last five characters at least (owner, 2026-10-28;
    // staff check every code against the statement); PaymentClaimService trims before it counts.
    public record ClaimRequest(
            @NotBlank(message = "Enter the M-PESA code from your confirmation message")
            @Size(min = 5, max = 60, message = "Enter the last 5 characters of the M-PESA code from your confirmation message")
            String transactionCode,
            @NotNull @DecimalMin("1") BigDecimal amount,
            LocalDate paidOn,
            @Size(max = 200) String payerName) {}

    // ---- staff requests
    /** {@code fullCode}: on approval, the whole M-PESA code staff may type from the statement for the record. */
    public record ResolveRequest(@Size(max = 1000) String note, @Size(max = 20) String fullCode) {}

    /** What staff noted after calling a customer about an unpaid order ("called, will pay at 3pm"); empty clears it. */
    public record ChaseNoteRequest(@Size(max = 300) String note) {}

    public record RecordCreditRequest(
            @NotBlank @Size(max = 100) String bankTxnId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @Size(max = 200) String payerName,
            @Size(max = 30) String payerPhone,
            @Size(max = 1000) String narration,
            @NotNull Instant receivedAt) {}

    public record AllocateRequest(@NotBlank String orderNo, @Size(max = 1000) String note) {}

    // ---- staff responses
    /**
     * {@code orderStatus} is PENDING_PAYMENT, or EXPIRED when the hold lapsed (a late payment can still be
     * confirmed). {@code expectedAccount} is what the customer was told to type as the paybill account
     * (their name, by default) — what staff look for on the M-PESA statement; empty if no paybill is set up.
     */
    public record AwaitingPaymentDto(
            UUID paymentId, String orderNo, String customerName, String phone, String email,
            BigDecimal amount, String reference, Instant expiresAt, Instant createdAt, boolean underReview,
            String orderStatus, String expectedAccount,
            /** NONE (the customer has not entered an M-PESA code), PENDING (one is waiting for a check) or REJECTED. */
            String codeStatus,
            /** The code waiting for a check, if any. */
            String submittedCode,
            /** What staff noted after calling the customer, who and when (V80); null until someone does. */
            String chaseNote, String chaseNoteBy, Instant chaseNoteAt) {

        public static AwaitingPaymentDto from(PaymentRecord p, PaymentCodeStatus codeStatus, String submittedCode,
                                              String expectedAccount) {
            var o = p.getOrder();
            return new AwaitingPaymentDto(p.getId(), o.getOrderNo(), o.getCustomerName(), o.getGuestPhone(),
                    o.getGuestEmail(), p.getAmount(), p.getPaymentReference(), p.getExpiresAt(), p.getCreatedAt(),
                    codeStatus == PaymentCodeStatus.PENDING, o.getStatus().name(), expectedAccount,
                    codeStatus.name(), submittedCode, o.getChaseNote(), o.getChaseNoteBy(), o.getChaseNoteAt());
        }
    }

    public record ClaimDto(
            UUID id, String orderNo, String customerName, String phone, BigDecimal orderAmount,
            String reference, String transactionCode, BigDecimal amountClaimed, LocalDate paidOn,
            String payerName, String status, Instant createdAt, String resolvedBy, String resolutionNote,
            /** Another order already paid with a code ending the same way: staff look twice at the statement. */
            String sameEndingOrderNo) {

        public static ClaimDto from(PaymentClaim c) {
            return from(c, null);
        }

        public static ClaimDto from(PaymentClaim c, String sameEndingOrderNo) {
            var p = c.getPayment();
            var o = p.getOrder();
            return new ClaimDto(c.getId(), o.getOrderNo(), o.getCustomerName(), o.getGuestPhone(), p.getAmount(),
                    p.getPaymentReference(), c.getTransactionCode(), c.getAmountClaimed(), c.getPaidOn(),
                    c.getPayerName(), c.getStatus().name(), c.getCreatedAt(), c.getResolvedBy(),
                    c.getResolutionNote(), sameEndingOrderNo);
        }
    }

    public record CreditDto(
            UUID id, String bankTxnId, BigDecimal amount, String payerName, String payerPhone, String narration,
            Instant receivedAt, String source, String status, String matchedOrderNo, String resolvedBy,
            String resolutionNote) {

        public static CreditDto from(BankCredit c) {
            return new CreditDto(c.getId(), c.getBankTxnId(), c.getAmount(), c.getPayerName(), c.getPayerPhone(),
                    c.getNarration(), c.getReceivedAt(), c.getSource().name(), c.getStatus().name(),
                    c.getPayment() == null ? null : c.getPayment().getOrder().getOrderNo(),
                    c.getResolvedBy(), c.getResolutionNote());
        }
    }
}
