package com.nbh.backend.payment;

import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.BankCreditService;
import com.nbh.backend.payment.service.PaymentClaimService;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "I've paid" claims and bank credits against real Postgres. The properties that
 * matter: a claim never confirms anything by itself, staff approval is what
 * moves the order to PAID, a bank credit auto-matches only on a valid reference
 * AND an exact amount, and nothing is ever matched to an order that is already
 * paid (that would hide a duplicate payment).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentClaimsAndCreditsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private PaymentReferenceGenerator referenceGenerator;
    @Autowired private PaymentClaimService claimService;
    @Autowired private BankCreditService creditService;

    private String orderNo;
    private String reference;
    private PaymentRecord payment;

    @BeforeEach
    void setUp() {
        orderNo = "CLAIM-" + UUID.randomUUID().toString().substring(0, 8);
        reference = referenceGenerator.generate();
        payment = newBankPayment(orderNo, reference);
    }

    private String claimBody(String code) {
        return "{\"transactionCode\":\"" + code + "\",\"amount\":500,\"payerName\":\"Jane\"}";
    }

    private String uniq(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Test
    void aClaimIsRecordedMarksThePageUnderReviewAndDoesNotConfirmTheOrder() throws Exception {
        mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody(uniq("QK"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.underReview").value(true));

        assertEquals(OrderStatus.PENDING_PAYMENT, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
    }

    @Test
    void theSameTransactionCodeTwiceIs409AndTheFourthPendingClaimIs429() throws Exception {
        String code = uniq("QD");
        mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                .contentType(MediaType.APPLICATION_JSON).content(claimBody(code))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                .contentType(MediaType.APPLICATION_JSON).content(claimBody(code.toLowerCase())))
                .andExpect(status().isConflict());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                    .contentType(MediaType.APPLICATION_JSON).content(claimBody(uniq("QX")))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                .contentType(MediaType.APPLICATION_JSON).content(claimBody(uniq("QY"))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aClaimForTheWrongReferenceIs404() throws Exception {
        mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", "BCDFGH0")
                .contentType(MediaType.APPLICATION_JSON).content(claimBody(uniq("QW")))).andExpect(status().isNotFound());
    }

    @Test
    void approvingAClaimPaysTheOrderAndCannotBeApprovedTwice() {
        PaymentClaim claim = claimService.submit(orderNo, reference, uniq("QA"), new BigDecimal("500"), null, "Jane");

        claimService.approve(claim.getId(), "staff@example.com", "checked statement");

        assertEquals(OrderStatus.PAID, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
        assertThrows(RuntimeException.class, () -> claimService.approve(claim.getId(), "staff@example.com", null));
        assertThrows(RuntimeException.class, () -> claimService.reject(claim.getId(), "staff@example.com", "late"));
    }

    @Test
    void rejectingNeedsAReasonAndLeavesTheOrderUnpaid() {
        PaymentClaim claim = claimService.submit(orderNo, reference, uniq("QR"), new BigDecimal("500"), null, null);

        assertThrows(IllegalArgumentException.class, () -> claimService.reject(claim.getId(), "staff@example.com", " "));
        claimService.reject(claim.getId(), "staff@example.com", "Code not on our statement");

        assertEquals(OrderStatus.PENDING_PAYMENT, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
        assertEquals(PaymentRecord.PaymentStatus.INITIATED,
                paymentRecordRepository.findById(payment.getId()).orElseThrow().getStatus());
    }

    @Test
    void aCreditWithTheReferenceAndExactAmountPaysTheOrderAutomatically() {
        BankCredit credit = creditService.record(uniq("TX"), new BigDecimal("500.00"), "Jane", null,
                "MPESA " + reference + " order", Instant.now(), BankCredit.Source.MANUAL);

        assertEquals(BankCredit.CreditStatus.MATCHED, credit.getStatus());
        assertEquals(OrderStatus.PAID, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
    }

    @Test
    void recordingTheSameBankTransactionTwiceChangesNothing() {
        String txn = uniq("TD");
        BankCredit first = creditService.record(txn, new BigDecimal("500.00"), null, null, "ref " + reference,
                Instant.now(), BankCredit.Source.MANUAL);
        BankCredit second = creditService.record(txn, new BigDecimal("999.00"), null, null, "other",
                Instant.now(), BankCredit.Source.IPN);

        assertEquals(first.getId(), second.getId());
        assertEquals(new BigDecimal("500.00"), second.getAmount());
    }

    @Test
    void aWrongAmountIsNeverAutoMatchedButIsFlaggedForStaff() {
        BankCredit credit = creditService.record(uniq("TW"), new BigDecimal("300.00"), null, null,
                reference, Instant.now(), BankCredit.Source.MANUAL);

        assertEquals(BankCredit.CreditStatus.UNMATCHED, credit.getStatus());
        assertNotNull(credit.getResolutionNote());
        assertEquals(OrderStatus.PENDING_PAYMENT, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
    }

    @Test
    void aSecondCreditForAnAlreadyPaidOrderIsNotSilentlyMatched() {
        creditService.record(uniq("T1"), new BigDecimal("500.00"), null, null, reference, Instant.now(),
                BankCredit.Source.MANUAL);
        BankCredit duplicate = creditService.record(uniq("T2"), new BigDecimal("500.00"), null, null, reference,
                Instant.now(), BankCredit.Source.MANUAL);

        assertEquals(BankCredit.CreditStatus.UNMATCHED, duplicate.getStatus());
        assertNull(duplicate.getPayment());
    }

    @Test
    void staffCanAllocateAnUnmatchedCreditToAnOrderAndIgnoreOthersWithAReason() {
        BankCredit credit = creditService.record(uniq("TA"), new BigDecimal("500.00"), "Jane", null,
                "no reference here", Instant.now(), BankCredit.Source.MANUAL);
        assertEquals(BankCredit.CreditStatus.UNMATCHED, credit.getStatus());

        BankCredit allocated = creditService.allocate(credit.getId(), orderNo, "staff@example.com", "customer called");
        assertEquals(BankCredit.CreditStatus.MATCHED, allocated.getStatus());
        assertEquals(OrderStatus.PAID, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());

        BankCredit other = creditService.record(uniq("TI"), new BigDecimal("100.00"), null, null, "rent",
                Instant.now(), BankCredit.Source.MANUAL);
        assertThrows(IllegalArgumentException.class, () -> creditService.ignore(other.getId(), "staff@example.com", ""));
        assertSame(BankCredit.CreditStatus.IGNORED,
                creditService.ignore(other.getId(), "staff@example.com", "Not an order").getStatus());
    }

    private PaymentRecord newBankPayment(String no, String ref) {
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(no);
        order.setGuestEmail("payer@example.com");
        order.setGuestPhone("254700000000");
        order.setFulfilmentType(FulfilmentType.PICKUP);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setSubtotal(new BigDecimal("500.00"));
        order.setVatTotal(BigDecimal.ZERO);
        order.setGrandTotal(new BigDecimal("500.00"));
        order = orderRepository.save(order);

        PaymentRecord record = new PaymentRecord();
        record.setOrder(order);
        record.setProvider("BANK");
        record.setMethod(PaymentRecord.PaymentMethod.BANK);
        record.setAmount(new BigDecimal("500.00"));
        record.setStatus(PaymentRecord.PaymentStatus.INITIATED);
        record.setPaymentReference(ref);
        record.setExpiresAt(Instant.now().plusSeconds(2700));
        return paymentRecordRepository.save(record);
    }
}
