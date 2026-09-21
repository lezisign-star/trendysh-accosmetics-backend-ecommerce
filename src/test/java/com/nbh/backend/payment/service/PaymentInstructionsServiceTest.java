package com.nbh.backend.payment.service;

import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentInstructionsServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:30:00Z"), ZoneOffset.UTC);
    private final PaymentReferenceGenerator generator = new PaymentReferenceGenerator();
    private PaymentRecordRepository repository;
    private PaymentInstructionsService service;
    private PaymentRecord payment;
    private CustomerOrder order;
    private String reference;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRecordRepository.class);
        var settings = new BankPaymentSettings(BankPaymentSettings.CollectionMode.BANK, 45, "Co-op Bank", "400200",
                "01100123#<ref>", "Trendysh Accosmetic", "01100123456789", "Nairobi", "254700000000");
        service = new PaymentInstructionsService(repository, mock(com.nbh.backend.payment.repository.PaymentClaimRepository.class),
                generator, settings, clock);

        reference = generator.generate();
        order = new CustomerOrder();
        order.setOrderNo("SO-20260101-000001");
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        payment = new PaymentRecord();
        payment.setOrder(order);
        payment.setPaymentReference(reference);
        payment.setAmount(new BigDecimal("4850.00"));
        payment.setStatus(PaymentRecord.PaymentStatus.INITIATED);
        payment.setExpiresAt(Instant.parse("2026-01-01T00:45:00Z"));
        when(repository.findByPaymentReference(reference)).thenReturn(Optional.of(payment));
    }

    @Test
    void pendingOrderShowsPaybillAccountWithTheTemplateAppliedAndTheTransferReference() {
        var dto = service.forOrder("SO-20260101-000001", reference);

        assertThat(dto.status()).isEqualTo("PENDING");
        assertThat(dto.paybill().paybillNumber()).isEqualTo("400200");
        assertThat(dto.paybill().accountNumber()).isEqualTo("01100123#" + reference);
        assertThat(dto.bankTransfer().reference()).isEqualTo(reference);
        assertThat(dto.amount()).isEqualByComparingTo("4850.00");
        assertThat(dto.expiresAt()).isEqualTo(Instant.parse("2026-01-01T00:45:00Z"));
    }

    @Test
    void referenceIsAcceptedLowerCaseAndSpaced() {
        var typed = reference.substring(0, 3).toLowerCase() + " " + reference.substring(3);
        assertThat(service.forOrder("SO-20260101-000001", typed).reference()).isEqualTo(reference);
    }

    @Test
    void wrongOrderNumberOrBadReferenceIsAnIndistinguishable404() {
        assertThatThrownBy(() -> service.forOrder("SO-20260101-999999", reference))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.forOrder("SO-20260101-000001", "NOTAREF"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void successfulPaymentReportsPaid() {
        payment.setStatus(PaymentRecord.PaymentStatus.SUCCESS);
        order.setStatus(OrderStatus.PAID);
        assertThat(service.forOrder("SO-20260101-000001", reference).status()).isEqualTo("PAID");
    }

    @Test
    void passedWindowOnAStillPendingOrderReportsExpired() {
        payment.setExpiresAt(Instant.parse("2026-01-01T00:10:00Z"));
        assertThat(service.forOrder("SO-20260101-000001", reference).status()).isEqualTo("EXPIRED");
    }

    @Test
    void failedAndCancelledOrdersReportFailedAndExpired() {
        order.setStatus(OrderStatus.PAYMENT_FAILED);
        assertThat(service.forOrder("SO-20260101-000001", reference).status()).isEqualTo("FAILED");
        order.setStatus(OrderStatus.CANCELLED);
        assertThat(service.forOrder("SO-20260101-000001", reference).status()).isEqualTo("EXPIRED");
    }
}
