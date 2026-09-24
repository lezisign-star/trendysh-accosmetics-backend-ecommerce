package com.nbh.backend.payment;

import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.entity.PaymentSettings;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.repository.PaymentSettingsRepository;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import com.nbh.backend.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer's bank pay page against real Postgres and the real security
 * chain: the endpoint is public (no token), authorised only by order number +
 * payment reference, and reflects the payment moving to PAID. Also pins the
 * unique index on payment_reference (V19) that checkout's collision handling
 * relies on.
 *
 * Bank/paybill/account details now live in the {@code payment_settings} table
 * (admin-editable), not env-var config, so this test sets them directly via
 * {@link PaymentSettingsRepository} rather than {@code @TestPropertySource} —
 * that table is a real, suite-shared row, and whichever test class's context
 * happens to boot first would otherwise silently decide what every other test
 * sees (the seeder only ever populates it once, the first time it's empty).
 * Only {@code paybill-account-template} is still env-var config (deliberately
 * not admin-editable — see {@code BankPaymentSettings}'s javadoc).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = "app.bank.paybill-account-template=01100123#<ref>")
class BankPaymentPageIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private BranchRepository branchRepository;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private PaymentSettingsRepository paymentSettingsRepository;
    @Autowired private PaymentReferenceGenerator referenceGenerator;
    @Autowired private PaymentService paymentService;

    private String orderNo;
    private String reference;
    private PaymentRecord payment;

    @BeforeEach
    void setUp() {
        PaymentSettings settings = paymentSettingsRepository.findFirstByOrderByCreatedAtAsc()
                .orElseGet(PaymentSettings::new);
        settings.setBankName("Test Bank");
        settings.setPaybillNumber("400200");
        settings.setAccountName("Trendysh Accosmetic");
        settings.setAccountNumber("01100123456789");
        paymentSettingsRepository.save(settings);

        orderNo = "PAYPAGE-" + UUID.randomUUID().toString().substring(0, 8);
        reference = referenceGenerator.generate();
        payment = newBankPayment(newOrder(orderNo), reference);
    }

    @Test
    void publicPayPageShowsInstructionsThenPaidOnceConfirmed() throws Exception {
        mvc.perform(get("/api/v1/payments/pay/" + orderNo).param("ref", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.amount").value(500.0))
                .andExpect(jsonPath("$.paybill.paybillNumber").value("400200"))
                .andExpect(jsonPath("$.paybill.accountNumber").value("01100123#" + reference))
                .andExpect(jsonPath("$.bankTransfer.accountNumber").value("01100123456789"))
                .andExpect(jsonPath("$.bankTransfer.reference").value(reference));

        paymentService.confirmSuccess(payment.getId(), "BANK-TXN-" + UUID.randomUUID(),"{\"type\":\"test\"}");

        mvc.perform(get("/api/v1/payments/pay/" + orderNo).param("ref", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void aWrongReferenceOrWrongOrderNumberIsA404() throws Exception {
        mvc.perform(get("/api/v1/payments/pay/" + orderNo).param("ref", "BCDFGH0"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/pay/SOME-OTHER-ORDER").param("ref", reference))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/pay/" + orderNo))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePaymentReferenceIsUniqueAcrossPaymentRecords() {
        CustomerOrder other = newOrder("PAYPAGE-" + UUID.randomUUID().toString().substring(0, 8));
        assertThrows(DataIntegrityViolationException.class, () -> newBankPayment(other, reference));
    }

    private CustomerOrder newOrder(String no) {
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(no);
        order.setGuestEmail("payer@example.com");
        order.setGuestPhone("254700000000");
        order.setFulfilmentBranch(branchRepository.findByCode("BR-A").orElseThrow());
        order.setFulfilmentType(FulfilmentType.BRANCH_PICKUP);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setSubtotal(new BigDecimal("500.00"));
        order.setVatTotal(BigDecimal.ZERO);
        order.setGrandTotal(new BigDecimal("500.00"));
        return orderRepository.save(order);
    }

    private PaymentRecord newBankPayment(CustomerOrder order, String ref) {
        PaymentRecord record = new PaymentRecord();
        record.setOrder(order);
        record.setProvider("BANK");
        record.setMethod(PaymentRecord.PaymentMethod.BANK);
        record.setAmount(new BigDecimal("500.00"));
        record.setStatus(PaymentRecord.PaymentStatus.INITIATED);
        record.setPaymentReference(ref);
        record.setExpiresAt(Instant.now().plus(45, ChronoUnit.MINUTES));
        return paymentRecordRepository.save(record);
    }
}
