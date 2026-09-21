package com.nbh.backend.payment.service;

import com.nbh.backend.payment.dto.AdminPaymentDtos.AwaitingPaymentDto;
import com.nbh.backend.payment.dto.AdminPaymentDtos.ClaimDto;
import com.nbh.backend.payment.dto.AdminPaymentDtos.CreditDto;
import com.nbh.backend.payment.dto.AdminPaymentDtos.RecordCreditRequest;
import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.repository.PaymentClaimRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The staff payments screen's facade: every method maps to DTOs inside its own
 * transaction, so controllers never touch a lazy association (the bug class
 * ControllerArchitectureTest guards).
 */
@Service
public class AdminPaymentService {

    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentClaimRepository claimRepository;
    private final PaymentClaimService claimService;
    private final BankCreditService creditService;

    public AdminPaymentService(PaymentRecordRepository paymentRecordRepository,
                                PaymentClaimRepository claimRepository,
                                PaymentClaimService claimService,
                                BankCreditService creditService) {
        this.paymentRecordRepository = paymentRecordRepository;
        this.claimRepository = claimRepository;
        this.claimService = claimService;
        this.creditService = creditService;
    }

    @Transactional(readOnly = true)
    public List<AwaitingPaymentDto> awaiting() {
        Set<UUID> underReview = new HashSet<>();
        for (PaymentClaim c : claimRepository.findByStatusOrderByCreatedAtAsc(PaymentClaim.ClaimStatus.PENDING)) {
            underReview.add(c.getPayment().getId());
        }
        return paymentRecordRepository.findAwaitingBankPayments().stream()
                .map(p -> AwaitingPaymentDto.from(p, underReview.contains(p.getId())))
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
