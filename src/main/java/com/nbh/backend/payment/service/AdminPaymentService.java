package com.nbh.backend.payment.service;

import com.nbh.backend.payment.dto.AdminPaymentDtos.AwaitingPaymentDto;
import com.nbh.backend.payment.dto.AdminPaymentDtos.ClaimDto;
import com.nbh.backend.payment.dto.AdminPaymentDtos.CreditDto;
import com.nbh.backend.payment.dto.AdminPaymentDtos.RecordCreditRequest;
import com.nbh.backend.payment.dto.PaymentCodeStatus;
import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentClaimRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The staff payments screen's facade: every method maps to DTOs inside its own
 * transaction, so controllers never touch a lazy association (the bug class
 * ControllerArchitectureTest guards).
 */
@Service
public class AdminPaymentService {

    /** How long an expired order stays in the awaiting queue, in case its customer pays late. */
    private static final Duration EXPIRED_ORDER_VISIBILITY = Duration.ofDays(30);

    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentClaimRepository claimRepository;
    private final PaymentClaimService claimService;
    private final BankCreditService creditService;
    private final BankPaymentSettings paymentSettings;
    private final Clock clock;

    public AdminPaymentService(PaymentRecordRepository paymentRecordRepository,
                                PaymentClaimRepository claimRepository,
                                PaymentClaimService claimService,
                                BankCreditService creditService,
                                BankPaymentSettings paymentSettings,
                                Clock clock) {
        this.paymentRecordRepository = paymentRecordRepository;
        this.claimRepository = claimRepository;
        this.claimService = claimService;
        this.creditService = creditService;
        this.paymentSettings = paymentSettings;
        this.clock = clock;
    }

    /**
     * Every unpaid order, with where its M-PESA code stands. An order the customer placed and then walked away from
     * (to pay on their phone) has no code and is listed here all the same: that is the point of keeping it visible.
     */
    @Transactional(readOnly = true)
    public List<AwaitingPaymentDto> awaiting() {
        List<PaymentRecord> payments = paymentRecordRepository
                .findAwaitingBankPayments(Instant.now(clock).minus(EXPIRED_ORDER_VISIBILITY));
        // One query for every code on every listed payment, newest first.
        Map<UUID, List<PaymentClaim>> claimsByPayment = payments.isEmpty() ? Map.of()
                : claimRepository.findByPaymentIdInOrderByCreatedAtDesc(payments.stream().map(PaymentRecord::getId).toList())
                        .stream().collect(Collectors.groupingBy(c -> c.getPayment().getId()));
        boolean paybillConfigured = !paymentSettings.paybillNumber().isBlank();
        return payments.stream()
                .map(p -> {
                    var o = p.getOrder();
                    String expected = paybillConfigured
                            ? paymentSettings.paybillAccountFor(o.getCustomerName(), o.getGuestPhone(), o.getOrderNo(), p.getPaymentReference())
                            : "";
                    List<PaymentClaim> claims = claimsByPayment.getOrDefault(p.getId(), List.of());
                    PaymentCodeStatus codeStatus = PaymentCodeStatus.of(claims.stream().map(PaymentClaim::getStatus).toList());
                    String submittedCode = claims.stream()
                            .filter(c -> c.getStatus() == PaymentClaim.ClaimStatus.PENDING)
                            .map(PaymentClaim::getTransactionCode)
                            .findFirst().orElse(null);
                    return AwaitingPaymentDto.from(p, codeStatus, submittedCode, expected);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimDto> pendingClaims() {
        return claimService.pending().stream().map(ClaimDto::from).toList();
    }

    @Transactional
    public ClaimDto approveClaim(UUID id, String staffEmail, String note) {
        return ClaimDto.from(claimService.approve(id, staffEmail, note));
    }

    @Transactional
    public ClaimDto rejectClaim(UUID id, String staffEmail, String note) {
        return ClaimDto.from(claimService.reject(id, staffEmail, note));
    }

    @Transactional(readOnly = true)
    public List<CreditDto> unmatchedCredits() {
        return creditService.unmatched().stream().map(CreditDto::from).toList();
    }

    @Transactional
    public CreditDto recordCredit(RecordCreditRequest r) {
        return CreditDto.from(creditService.record(r.bankTxnId(), r.amount(), r.payerName(), r.payerPhone(),
                r.narration(), r.receivedAt(), BankCredit.Source.MANUAL));
    }

    @Transactional
    public CreditDto allocateCredit(UUID id, String orderNo, String staffEmail, String note) {
        return CreditDto.from(creditService.allocate(id, orderNo, staffEmail, note));
    }

    @Transactional
    public CreditDto ignoreCredit(UUID id, String staffEmail, String note) {
        return CreditDto.from(creditService.ignore(id, staffEmail, note));
    }
}
