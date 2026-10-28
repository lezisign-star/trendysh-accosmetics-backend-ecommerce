package com.nbh.backend.payment.service;

import com.nbh.backend.common.event.PaymentClaimSubmittedEvent;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
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
    /**
     * The shortest M-PESA code taken: its last five characters (owner, 2026-10-28), the part of a code that differs most
     * from one payment to the next. Staff check every code against the statement, where the order's own account
     * reference and amount already tie the payment to the order; a customer may still send the whole code.
     */
    static final int MIN_CODE_LENGTH = 5;
    /** A whole M-PESA code is 10 characters; anything this long or longer is taken as the whole code, not an ending. */
    static final int FULL_CODE_LENGTH = 8;

    /**
     * Whether a customer may report paying an amount other than the order's, or from someone else's name. Off
     * (owner, 2026-10-10: "faded/disabled for now"): the pay page no longer offers it, and a claim for any other
     * amount is refused here too, so an old open page cannot sneak one in.
     */
    @Value("${app.payments.allow-different-amount:false}")
    private boolean allowDifferentAmount;

    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentClaimRepository claimRepository;
    private final PaymentReferenceGenerator referenceGenerator;
    private final PaymentService paymentService;
    private final StockReservationService reservationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public PaymentClaimService(PaymentRecordRepository paymentRecordRepository,
                                PaymentClaimRepository claimRepository,
                                PaymentReferenceGenerator referenceGenerator,
                                PaymentService paymentService,
                                StockReservationService reservationService,
                                ApplicationEventPublisher eventPublisher,
                                Clock clock) {
        this.paymentRecordRepository = paymentRecordRepository;
        this.claimRepository = claimRepository;
        this.referenceGenerator = referenceGenerator;
        this.paymentService = paymentService;
        this.reservationService = reservationService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public PaymentClaim submit(String orderNo, String reference, String transactionCode, BigDecimal amount,
                               LocalDate paidOn, String payerName) {
        // M-PESA codes are upper case; storing them that way means staff compare like with like on the statement.
        String code = transactionCode == null ? "" : transactionCode.trim().toUpperCase(Locale.ROOT);
        if (code.length() < MIN_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "Enter the M-PESA code from your confirmation message (at least " + MIN_CODE_LENGTH + " characters)");
        }
        String normalised = referenceGenerator.normaliseAndValidate(reference);
        UUID paymentId = normalised == null ? null
                : paymentRecordRepository.findIdByReferenceAndOrderNo(normalised, orderNo).orElse(null);
        if (paymentId == null) {
            throw new NotFoundException("No payment found for that order and reference");
        }
        // The payment row is locked FIRST, before anything else is touched, because confirmSuccess takes
        // its locks payment, then order, then stock: extending the stock holds below while another
        // transaction confirms this same payment would otherwise be two transactions each holding what the
        // other needs next. It also makes two simultaneous claims on one payment take turns, so the pending
        // cap and the "first claim" test below see each other.
        PaymentRecord payment = paymentRecordRepository.lockById(paymentId)
                .orElseThrow(() -> new NotFoundException("No payment found for that order and reference"));
        if (payment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
            throw new ConflictException("This order has already been paid");
        }
        // Staff marked this payment failed (or it was reversed): a claim on it could never be approved, so it would
        // only sit in the queue as a dead end. The customer is better told now to contact the shop.
        if (payment.getStatus() == PaymentRecord.PaymentStatus.FAILED
                || payment.getStatus() == PaymentRecord.PaymentStatus.REVERSED) {
            throw new ConflictException("This payment was cancelled by the shop, so we can't take a payment code for it."
                    + " Please message us on WhatsApp and we'll sort it out.");
        }
        // Staff cancelled the whole order, which leaves its payment untouched: approving a code on a cancelled order is
        // refused as well, so a code taken now would only wait in the queue to be turned down. The pay page shows a
        // cancelled order as expired (and expired ones do take a late code), so say plainly what happened instead.
        if (payment.getOrder().getStatus() == OrderStatus.CANCELLED) {
            throw new ConflictException("This order was cancelled by the shop, so we can't take a payment code for it."
                    + " If you have already paid, please message us on WhatsApp and we'll refund you.");
        }
        if (!allowDifferentAmount && amount != null && amount.compareTo(payment.getAmount()) != 0) {
            throw new IllegalArgumentException("Paying a different amount is not available online yet. Please pay the"
                    + " exact amount shown, or message us on WhatsApp and we'll sort it out.");
        }
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
        // Staff have to check this against the statement: tell any open dashboard as soon as it commits.
        eventPublisher.publishEvent(new PaymentClaimSubmittedEvent(
                claim.getId(), payment.getId(), payment.getOrder().getOrderNo(), amount));
        return claim;
    }

    @Transactional
    public PaymentClaim approve(UUID claimId, String staffEmail, String note) {
        return approve(claimId, staffEmail, note, null);
    }

    /**
     * {@code fullCode} is the whole M-PESA code staff may type from the statement for the record (optional, owner
     * 2026-10-28: one tap is enough). It must end with what the customer sent.
     */
    @Transactional
    public PaymentClaim approve(UUID claimId, String staffEmail, String note, String fullCode) {
        PaymentClaim claim = lockPending(claimId);
        String typed = fullCode == null ? "" : fullCode.trim().toUpperCase(Locale.ROOT);
        if (!typed.isEmpty()) {
            if (typed.length() < FULL_CODE_LENGTH || !typed.chars().allMatch(Character::isLetterOrDigit)) {
                throw new IllegalArgumentException("Type the whole M-PESA code from the statement, or leave it empty");
            }
            if (!typed.endsWith(claim.getTransactionCode())) {
                throw new IllegalArgumentException("The code " + typed + " does not end in " + claim.getTransactionCode()
                        + ", which the customer sent. Check the statement again");
            }
        }
        PaymentRecord payment = claim.getPayment();
        // Approving marks the whole order paid, so when the customer reported a different amount the person
        // approving has to say what they agreed (balance in cash, part payment accepted) and it is kept.
        if (claim.getAmountClaimed() != null && payment.getAmount() != null
                && claim.getAmountClaimed().compareTo(payment.getAmount()) != 0
                && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("The customer reported paying " + claim.getAmountClaimed().toPlainString()
                    + " but the order is " + payment.getAmount().toPlainString()
                    + ". Add a note saying what you agreed before approving");
        }
        // confirmSuccess is idempotent on an already-SUCCESS payment and throws (rolling this whole
        // approval back) if the hold already lapsed, in which case staff must re-check stock first.
        // The payment keeps a whole code as its reference, where "one code pays one order" is checked. Only the last five
        // (the customer's, with nothing typed by staff) are not kept there: two different payments can share an ending.
        String reference = !typed.isEmpty() ? typed
                : claim.getTransactionCode().length() >= FULL_CODE_LENGTH ? claim.getTransactionCode() : null;
        paymentService.confirmSuccess(payment.getId(), reference,
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
