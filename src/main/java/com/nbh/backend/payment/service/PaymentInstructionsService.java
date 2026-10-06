package com.nbh.backend.payment.service;

import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.payment.dto.PaymentCodeStatus;
import com.nbh.backend.payment.dto.PaymentInstructionsDto;
import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentClaimRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Builds the payment-instructions view for a bank-collected order, both right
 * after checkout and on every later refresh/poll of the pay page. The payment
 * reference is the bearer secret: knowing it (plus the order number) is what
 * lets an anonymous shopper read this order's payment state, and a wrong
 * combination is a plain 404 that does not reveal whether the order exists.
 */
@Service
public class PaymentInstructionsService {

    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentClaimRepository claimRepository;
    private final PaymentReferenceGenerator referenceGenerator;
    private final BankPaymentSettings settings;
    private final Clock clock;

    public PaymentInstructionsService(PaymentRecordRepository paymentRecordRepository,
                                       PaymentClaimRepository claimRepository,
                                       PaymentReferenceGenerator referenceGenerator,
                                       BankPaymentSettings settings,
                                       Clock clock) {
        this.paymentRecordRepository = paymentRecordRepository;
        this.claimRepository = claimRepository;
        this.referenceGenerator = referenceGenerator;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PaymentInstructionsDto forOrder(String orderNo, String reference) {
        String normalised = referenceGenerator.normaliseAndValidate(reference);
        if (normalised == null) {
            throw new NotFoundException("No payment found for that order and reference");
        }
        PaymentRecord payment = paymentRecordRepository.findByPaymentReference(normalised)
                .filter(p -> p.getOrder().getOrderNo().equals(orderNo))
                .orElseThrow(() -> new NotFoundException("No payment found for that order and reference"));
        return build(payment);
    }

    /** Callers must already hold the record (e.g. checkout); the order is read from it. */
    public PaymentInstructionsDto build(PaymentRecord payment) {
        CustomerOrder order = payment.getOrder();
        String reference = payment.getPaymentReference();

        PaymentInstructionsDto.Paybill paybill = settings.paybillNumber().isBlank() ? null
                : new PaymentInstructionsDto.Paybill(settings.paybillNumber(),
                        settings.paybillAccountFor(order.getCustomerName(), order.getGuestPhone(), order.getOrderNo(), reference),
                        settings.paybillMerchantName());
        PaymentInstructionsDto.BankTransfer transfer = settings.accountNumber().isBlank() ? null
                : new PaymentInstructionsDto.BankTransfer(settings.bankName(), settings.accountName(),
                        settings.accountNumber(), settings.accountBranch(), reference);

        List<PaymentClaim> claims = claimRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId());
        PaymentCodeStatus codeStatus = PaymentCodeStatus.of(claims.stream().map(PaymentClaim::getStatus).toList());
        String submittedCode = claims.stream()
                .filter(c -> c.getStatus() == PaymentClaim.ClaimStatus.PENDING)
                .map(PaymentClaim::getTransactionCode)
                .findFirst().orElse(null);

        return new PaymentInstructionsDto(order.getOrderNo(), reference, payment.getAmount(), "KES",
                displayStatus(order, payment), payment.getExpiresAt(), paybill, transfer,
                settings.supportWhatsapp(), codeStatus == PaymentCodeStatus.PENDING, codeStatus.name(), submittedCode);
    }

    /**
     * The pay-page reference for an order that is still waiting on money (or lapsed but still payable late), for the
     * order owner's own views only: it is what lets a signed-in customer, or a guest who has proved their email, get
     * back to the page that takes the M-PESA code. Null once the order is paid, closed, or has no bank payment.
     */
    @Transactional(readOnly = true)
    public String payReferenceFor(CustomerOrder order) {
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT && order.getStatus() != OrderStatus.EXPIRED) {
            return null;
        }
        return paymentRecordRepository.findByOrderId(order.getId()).stream()
                .filter(p -> p.getPaymentReference() != null
                        && (p.getStatus() == PaymentRecord.PaymentStatus.INITIATED
                            || p.getStatus() == PaymentRecord.PaymentStatus.PENDING))
                .map(PaymentRecord::getPaymentReference)
                .findFirst().orElse(null);
    }

    private String displayStatus(CustomerOrder order, PaymentRecord payment) {
        if (payment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
            return "PAID";
        }
        OrderStatus status = order.getStatus();
        if (status == OrderStatus.PAYMENT_FAILED) {
            return "FAILED";
        }
        if (status == OrderStatus.EXPIRED || status == OrderStatus.CANCELLED) {
            return "EXPIRED";
        }
        if (status == OrderStatus.PENDING_PAYMENT) {
            boolean windowPassed = payment.getExpiresAt() != null && payment.getExpiresAt().isBefore(Instant.now(clock));
            return windowPassed ? "EXPIRED" : "PENDING";
        }
        return "PAID"; // any later fulfilment stage implies the payment was accepted
    }
}
