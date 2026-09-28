package com.nbh.backend.payment.service;

import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.TooManyRequestsException;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentClaimRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.stock.service.StockReservationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "I've paid" claims. A claim NEVER confirms a payment by itself: the customer
 * typing a transaction code proves nothing. It puts the order in front of staff
 * (who check the bank statement) and stops the items being released while they
 * do. Approval goes through {@link PaymentService#confirmSuccess}, the one place
 * an order leaves PENDING_PAYMENT.
 */
@Service
public class PaymentClaimService {

    /** Long enough to cover a weekend/overnight without staff, short enough not to hoard stock forever. */
    static final Duration REVIEW_HOLD = Duration.ofHours(12);
    /** Stops a bot or a confused customer flooding the review queue. */
    static final int MAX_PENDING_CLAIMS_PER_PAYMENT = 3;

    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentClaimRepository claimRepository;
    private final PaymentReferenceGenerator referenceGenerator;
    private final PaymentService paymentService;
    private final StockReservationService reservationService;
    private final Clock clock;

    public PaymentClaimService(PaymentRecordRepository paymentRecordRepository,
                                PaymentClaimRepository claimRepository,
                                PaymentReferenceGenerator referenceGenerator,
                                PaymentService paymentService,
                                StockReservationService reservationService,
                                Clock clock) {
        this.paymentRecordRepository = paymentRecordRepository;
        this.claimRepository = claimRepository;
        this.referenceGenerator = referenceGenerator;
        this.paymentService = paymentService;
        this.reservationService = reservationService;
        this.clock = clock;
    }

    @Transactional
    public PaymentClaim submit(String orderNo, String reference, String transactionCode, BigDecimal amount,
                               LocalDate paidOn, String payerName) {
        String normalised = referenceGenerator.normaliseAndValidate(reference);
        PaymentRecord payment = normalised == null ? null
                : paymentRecordRepository.findByPaymentReference(normalised)
                        .filter(p -> p.getOrder().getOrderNo().equals(orderNo)).orElse(null);
        if (payment == null) {
            throw new NotFoundException("No payment found for that order and reference");
        }
        if (payment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
            throw new ConflictException("This order has already been paid");
        }
        String code = transactionCode.trim();
        if (claimRepository.existsByPaymentIdAndTransactionCodeIgnoreCase(payment.getId(), code)) {
            throw new ConflictException("You've already sent us that transaction code");
        }
        if (claimRepository.countByPaymentIdAndStatus(payment.getId(), PaymentClaim.ClaimStatus.PENDING)
                >= MAX_PENDING_CLAIMS_PER_PAYMENT) {
            throw new TooManyRequestsException(
                    "We're already checking your payment. Please wait for our confirmation or message us on WhatsApp.");
        }

        boolean firstClaim = claimRepository.countByPaymentId(payment.getId()) == 0;

        PaymentClaim claim = new PaymentClaim();
        claim.setPayment(payment);
        claim.setTransactionCode(code);
        claim.setAmountClaimed(amount);
        claim.setPaidOn(paidOn);
        claim.setPayerName(blankToNull(payerName));
        claim = claimRepository.save(claim);

        // Only the first claim buys the extra review time. Every claim used to push the hold out
        // another 12 hours from now, and staff rejecting one reset the pending count — so a
        // stream of made-up transaction codes could keep an order's stock held indefinitely.
        if (firstClaim) {
            holdItemsForReview(payment);
        }
        return claim;
    }

    @Transactional
    public PaymentClaim approve(UUID claimId, String staffEmail, String note) {
        PaymentClaim claim = lockPending(claimId);
        PaymentRecord payment = claim.getPayment();
        // confirmSuccess is idempotent on an already-SUCCESS payment and throws (rolling this whole
        // approval back) if the hold already lapsed, in which case staff must re-check stock first.
        paymentService.confirmSuccess(payment.getId(), claim.getTransactionCode(),
                "{\"type\":\"claim_approved\",\"claimId\":\"" + claim.getId() + "\"}",
                PaymentRecord.PaymentMethod.BANK);
        resolve(claim, PaymentClaim.ClaimStatus.APPROVED, staffEmail, note);
        return claim;
    }

    @Transactional
    public PaymentClaim reject(UUID claimId, String staffEmail, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Give a reason for rejecting the claim");
        }
        PaymentClaim claim = lockPending(claimId);
        resolve(claim, PaymentClaim.ClaimStatus.REJECTED, staffEmail, note);
        return claim;
    }

    @Transactional(readOnly = true)
    public List<PaymentClaim> pending() {
        return claimRepository.findByStatusOrderByCreatedAtAsc(PaymentClaim.ClaimStatus.PENDING);
    }

    private PaymentClaim lockPending(UUID claimId) {
        PaymentClaim claim = claimRepository.lockById(claimId)
                .orElseThrow(() -> new NotFoundException("No such claim: " + claimId));
        if (claim.getStatus() != PaymentClaim.ClaimStatus.PENDING) {
            throw new ConflictException("This claim has already been " + claim.getStatus().name().toLowerCase());
        }
        return claim;
    }

    private void resolve(PaymentClaim claim, PaymentClaim.ClaimStatus status, String staffEmail, String note) {
        claim.setStatus(status);
        claim.setResolvedBy(staffEmail);
        claim.setResolvedAt(Instant.now(clock));
        claim.setResolutionNote(blankToNull(note));
        claimRepository.save(claim);
    }

    /** Keeps the stock and the payment window alive for staff to check, never shortening either. */
    private void holdItemsForReview(PaymentRecord payment) {
        CustomerOrder order = payment.getOrder();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return;
        }
        Instant until = Instant.now(clock).plus(REVIEW_HOLD);
        for (CustomerOrderItem item : order.getItems()) {
            if (item.getReservation() != null) {
                reservationService.extendHold(item.getReservation().getId(), until);
            }
        }
        if (payment.getExpiresAt() == null || payment.getExpiresAt().isBefore(until)) {
            payment.setExpiresAt(until);
            paymentRecordRepository.save(payment);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
