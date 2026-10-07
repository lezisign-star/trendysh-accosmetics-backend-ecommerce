package com.nbh.backend.payment.service;

import com.nbh.backend.common.event.BankCreditRecordedEvent;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.BankCreditRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bank credits: money seen in the account, from staff reading the statement now
 * and from the bank's notification / reconciliation job later (same entry
 * point, so those steps plug straight in). Every credit is kept forever, and
 * one that cannot be matched with certainty is left UNMATCHED for staff rather
 * than guessed at, because a wrongly confirmed order ships goods for money that
 * belongs to someone else.
 */
@Service
public class BankCreditService {

    private final BankCreditRepository creditRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final CustomerOrderRepository orderRepository;
    private final PaymentReferenceGenerator referenceGenerator;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public BankCreditService(BankCreditRepository creditRepository,
                              PaymentRecordRepository paymentRecordRepository,
                              CustomerOrderRepository orderRepository,
                              PaymentReferenceGenerator referenceGenerator,
                              PaymentService paymentService,
                              ApplicationEventPublisher eventPublisher,
                              Clock clock) {
        this.creditRepository = creditRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.orderRepository = orderRepository;
        this.referenceGenerator = referenceGenerator;
        this.paymentService = paymentService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /** Idempotent on {@code bankTxnId}: recording the same bank transaction twice returns the first record untouched. */
    @Transactional
    public BankCredit record(String bankTxnId, BigDecimal amount, String payerName, String payerPhone,
                             String narration, Instant receivedAt, BankCredit.Source source) {
        return record(bankTxnId, amount, payerName, payerPhone, narration, receivedAt, source, true);
    }

    /**
     * {@code autoMatch=false} records the credit and leaves it UNMATCHED for staff to allocate. Used for a feed
     * we have not yet verified against the real bank: recording is always safe, confirming an order is not.
     */
    @Transactional
    public BankCredit record(String bankTxnId, BigDecimal amount, String payerName, String payerPhone,
                             String narration, Instant receivedAt, BankCredit.Source source, boolean autoMatch) {
        String txnId = bankTxnId.trim();
        BankCredit existing = creditRepository.findByBankTxnId(txnId).orElse(null);
        if (existing != null) {
            return existing;
        }

        BankCredit credit = new BankCredit();
        credit.setBankTxnId(txnId);
        credit.setAmount(amount);
        credit.setPayerName(blankToNull(payerName));
        credit.setPayerPhone(blankToNull(payerPhone));
        credit.setNarration(blankToNull(narration));
        credit.setReceivedAt(receivedAt);
        credit.setSource(source);
        credit = creditRepository.save(credit);

        if (autoMatch) {
            tryAutoMatch(credit);
        }
        // confirmAndMatch (inside tryAutoMatch) mutates this same object, so its status already
        // reflects whether the auto-match actually landed — a plain "money arrived" hint either way.
        boolean matched = credit.getStatus() == BankCredit.CreditStatus.MATCHED;
        String orderNo = matched && credit.getPayment() != null ? credit.getPayment().getOrder().getOrderNo() : null;
        eventPublisher.publishEvent(new BankCreditRecordedEvent(credit.getId(), credit.getAmount(), matched, orderNo));
        return credit;
    }

    /** Staff point an unmatched credit at an order (e.g. the customer typed the wrong reference). */
    @Transactional
    public BankCredit allocate(UUID creditId, String orderNo, String staffEmail, String note) {
        BankCredit credit = lockUnmatched(creditId);
        // The id only, never the order: confirming the payment locks the order, and a copy loaded here
        // would be what that lock returns, however much the order changed in the meantime.
        UUID orderId = orderRepository.findIdByOrderNo(orderNo.trim())
                .orElseThrow(() -> new NotFoundException("No such order: " + orderNo));
        UUID paymentId = paymentRecordRepository.findUnpaidBankPaymentIdsByOrderId(orderId).stream().findFirst()
                .orElseThrow(() -> new ConflictException(
                        "Order " + orderNo + " has no unpaid bank payment to allocate this credit to"));
        // Locked, then re-checked: the scalar query above can go stale the instant it runs (a claim
        // approval or a callback confirming this exact payment right now), the same way an unlocked
        // findByOrderId used to — see confirmAndMatch's callers for why nothing is decided before this.
        PaymentRecord payment = paymentRecordRepository.lockById(paymentId).orElseThrow();
        if (payment.getStatus() != PaymentRecord.PaymentStatus.INITIATED
                && payment.getStatus() != PaymentRecord.PaymentStatus.PENDING) {
            throw new ConflictException("Order " + orderNo + " has no unpaid bank payment to allocate this credit to");
        }
        confirmAndMatch(credit, payment, staffEmail, blankToNull(note));
        return credit;
    }

    @Transactional
    public BankCredit ignore(UUID creditId, String staffEmail, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Say why this credit is not an order payment");
        }
        BankCredit credit = lockUnmatched(creditId);
        credit.setStatus(BankCredit.CreditStatus.IGNORED);
        credit.setResolvedBy(staffEmail);
        credit.setResolvedAt(Instant.now(clock));
        credit.setResolutionNote(note.trim());
        return creditRepository.save(credit);
    }

    @Transactional(readOnly = true)
    public List<BankCredit> unmatched() {
        return creditRepository.findByStatusOrderByReceivedAtDesc(BankCredit.CreditStatus.UNMATCHED);
    }

    private void tryAutoMatch(BankCredit credit) {
        if (credit.getNarration() == null) {
            return;
        }
        for (String token : credit.getNarration().split("[^A-Za-z0-9]+")) {
            String reference = referenceGenerator.normaliseAndValidate(token);
            if (reference == null) {
                continue;
            }
            UUID paymentId = paymentRecordRepository.findIdByPaymentReference(reference).orElse(null);
            if (paymentId == null) {
                continue;
            }
            // Locked before its status or amount is read, not after: this reference might belong to a
            // payment a claim approval, a Co-op callback or a staff allocation is confirming at this exact
            // moment, and reading it unlocked could see a stale INITIATED — the same "already paid" it
            // would otherwise correctly refuse below, only missed because the row had already moved on.
            PaymentRecord payment = paymentRecordRepository.lockById(paymentId).orElseThrow();
            if (payment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
                credit.setResolutionNote("Reference " + reference + " belongs to an order that is already paid: "
                        + "possible duplicate or overpayment, needs a person to look at it.");
                creditRepository.save(credit);
                return;
            }
            if (payment.getStatus() == PaymentRecord.PaymentStatus.FAILED) {
                // Staff can revive a payment they marked failed, but putting stock back under an order is a
                // person's decision (the stock may have been sold since), never an automatic one.
                credit.setResolutionNote("Reference " + reference + " belongs to order " + payment.getOrder().getOrderNo()
                        + ", whose payment was marked failed: needs a person to look at it.");
                creditRepository.save(credit);
                return;
            }
            if (payment.getAmount().compareTo(credit.getAmount()) != 0) {
                credit.setResolutionNote("Reference " + reference + " matches order " + payment.getOrder().getOrderNo()
                        + " but the amount differs (expected " + payment.getAmount() + ", received "
                        + credit.getAmount() + ").");
                creditRepository.save(credit);
                return;
            }
            confirmAndMatch(credit, payment, "system", "Matched automatically on reference " + reference);
            return;
        }
    }

    private void confirmAndMatch(BankCredit credit, PaymentRecord payment, String resolvedBy, String note) {
        paymentService.confirmSuccess(payment.getId(), credit.getBankTxnId(),
                "{\"type\":\"bank_credit\",\"creditId\":\"" + credit.getId() + "\"}",
                PaymentRecord.PaymentMethod.BANK);
        credit.setStatus(BankCredit.CreditStatus.MATCHED);
        credit.setPayment(payment);
        credit.setResolvedBy(resolvedBy);
        credit.setResolvedAt(Instant.now(clock));
        credit.setResolutionNote(note);
        creditRepository.save(credit);
    }

    private BankCredit lockUnmatched(UUID creditId) {
        BankCredit credit = creditRepository.lockById(creditId)
                .orElseThrow(() -> new NotFoundException("No such bank credit: " + creditId));
        if (credit.getStatus() != BankCredit.CreditStatus.UNMATCHED) {
            throw new ConflictException("This credit has already been " + credit.getStatus().name().toLowerCase());
        }
        return credit;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
