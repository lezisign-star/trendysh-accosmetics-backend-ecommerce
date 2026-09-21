package com.nbh.backend.payment.service;

import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.BankCreditRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
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
    private final Clock clock;

    public BankCreditService(BankCreditRepository creditRepository,
                              PaymentRecordRepository paymentRecordRepository,
                              CustomerOrderRepository orderRepository,
                              PaymentReferenceGenerator referenceGenerator,
                              PaymentService paymentService,
                              Clock clock) {
        this.creditRepository = creditRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.orderRepository = orderRepository;
        this.referenceGenerator = referenceGenerator;
        this.paymentService = paymentService;
        this.clock = clock;
    }

    /** Idempotent on {@code bankTxnId}: recording the same bank transaction twice returns the first record untouched. */
    @Transactional
    public BankCredit record(String bankTxnId, BigDecimal amount, String payerName, String payerPhone,
                             String narration, Instant receivedAt, BankCredit.Source source) {
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

        tryAutoMatch(credit);
        return credit;
    }

    /** Staff point an unmatched credit at an order (e.g. the customer typed the wrong reference). */
    @Transactional
    public BankCredit allocate(UUID creditId, String orderNo, String staffEmail, String note) {
        BankCredit credit = lockUnmatched(creditId);
        UUID orderId = orderRepository.findByOrderNo(orderNo.trim())
                .orElseThrow(() -> new NotFoundException("No such order: " + orderNo)).getId();
        PaymentRecord payment = paymentRecordRepository.findByOrderId(orderId).stream()
                .filter(p -> p.getMethod() == PaymentRecord.PaymentMethod.BANK)
                .filter(p -> p.getStatus() == PaymentRecord.PaymentStatus.INITIATED
                        || p.getStatus() == PaymentRecord.PaymentStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "Order " + orderNo + " has no unpaid bank payment to allocate this credit to"));
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
            PaymentRecord payment = paymentRecordRepository.findByPaymentReference(reference).orElse(null);
            if (payment == null) {
                continue;
            }
            if (payment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
                credit.setResolutionNote("Reference " + reference + " belongs to an order that is already paid: "
                        + "possible duplicate or overpayment, needs a person to look at it.");
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
