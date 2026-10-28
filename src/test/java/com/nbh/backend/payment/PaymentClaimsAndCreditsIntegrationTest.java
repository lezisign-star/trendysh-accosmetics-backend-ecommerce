package com.nbh.backend.payment;

import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.AdminPaymentService;
import com.nbh.backend.payment.service.BankCreditService;
import com.nbh.backend.payment.service.PaymentClaimService;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import com.nbh.backend.payment.service.PaymentService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Autowired private PaymentService paymentService;
    @Autowired private AdminPaymentService adminPaymentService;

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

    /**
     * Found in the 2026-10-06 go-live QA: a customer could still send a payment code for an order whose payment staff
     * had already marked failed. Staff can never approve a claim on a failed payment, so it only sat in the queue as a
     * dead end. The customer is told at once to contact the shop.
     */
    @Test
    void aClaimOnAPaymentStaffMarkedFailedIs409SoItNeverSitsInTheQueueAsADeadEnd() throws Exception {
        payment.setStatus(PaymentRecord.PaymentStatus.FAILED);
        paymentRecordRepository.save(payment);

        mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody(uniq("QF"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("WhatsApp")));

        assertEquals(0, claimService.pending().stream().filter(c -> orderNo.equals(c.getPayment().getOrder().getOrderNo())).count());
    }

    @Test
    void aClaimForTheWrongReferenceIs404() throws Exception {
        mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", "BCDFGH0")
                .contentType(MediaType.APPLICATION_JSON).content(claimBody(uniq("QW")))).andExpect(status().isNotFound());
    }

    private String lastFive() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }

    /** Owner, 2026-10-28: the last five characters of the M-PESA code are enough; staff confirm in one tap. */
    @Test
    void theLastFiveCharactersAreTakenAndOneTapApprovalKeepsNoPartialCodeAsTheReference() throws Exception {
        String ending = lastFive();
        mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody(ending)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody("AB12")))
                .andExpect(status().isBadRequest());

        PaymentClaim claim = claimService.pending().stream()
                .filter(c -> orderNo.equals(c.getPayment().getOrder().getOrderNo())).findFirst().orElseThrow();
        claimService.approve(claim.getId(), "staff@example.com", null);

        assertEquals(OrderStatus.PAID, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
        // Two payments can share an ending, so five characters never become the "one code, one order" reference.
        assertNull(paymentRecordRepository.findById(payment.getId()).orElseThrow().getProviderReference());
    }

    @Test
    void aWholeCodeStaffTypeMustEndWithTheCustomersFiveAndIsKeptAsTheReference() {
        String ending = lastFive();
        PaymentClaim claim = claimService.submit(orderNo, reference, ending, new BigDecimal("500"), null, "Jane");

        assertThrows(IllegalArgumentException.class,
                () -> claimService.approve(claim.getId(), "staff@example.com", null, "SGH7AZZZZZ"));
        String whole = "SGH7A" + ending;
        claimService.approve(claim.getId(), "staff@example.com", null, whole.toLowerCase());

        assertEquals(whole, paymentRecordRepository.findById(payment.getId()).orElseThrow().getProviderReference());
    }

    @Test
    void aPendingFiveCharacterClaimSharingAnEndingWithAnApprovedOneNamesThatOrder() {
        String ending = lastFive();
        PaymentClaim first = claimService.submit(orderNo, reference, ending, new BigDecimal("500"), null, "Jane");
        claimService.approve(first.getId(), "staff@example.com", null);

        String otherNo = "CLAIM-" + UUID.randomUUID().toString().substring(0, 8);
        String otherRef = referenceGenerator.generate();
        newBankPayment(otherNo, otherRef);
        claimService.submit(otherNo, otherRef, ending, new BigDecimal("500"), null, "Ann");

        var listed = adminPaymentService.pendingClaims().stream()
                .filter(c -> otherNo.equals(c.orderNo())).findFirst().orElseThrow();
        assertEquals(orderNo, listed.sameEndingOrderNo());
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
    void aClaimForADifferentAmountIsRefusedWhileThatIsSwitchedOff() {
        assertThrows(IllegalArgumentException.class,
                () -> claimService.submit(orderNo, reference, uniq("QX"), new BigDecimal("300"), null, "Jane"));
        // The exact amount is still taken.
        claimService.submit(orderNo, reference, uniq("QY"), new BigDecimal("500"), null, null);
    }

    @Test
    void aDifferentAmountCanOnlyBeApprovedWithANote() {
        // Different amounts are off by default; this checks the rule that applies once they are switched on.
        org.springframework.test.util.ReflectionTestUtils.setField(claimService, "allowDifferentAmount", true);
        PaymentClaim claim;
        try {
            claim = claimService.submit(orderNo, reference, uniq("QD"), new BigDecimal("300"), null, "Jane");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(claimService, "allowDifferentAmount", false);
        }

        assertThrows(IllegalArgumentException.class, () -> claimService.approve(claim.getId(), "staff@example.com", null));
        assertThrows(IllegalArgumentException.class, () -> claimService.approve(claim.getId(), "staff@example.com", " "));
        assertEquals(OrderStatus.PENDING_PAYMENT, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());

        claimService.approve(claim.getId(), "staff@example.com", "balance paid in cash");
        assertEquals(OrderStatus.PAID, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
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

    /**
     * A bank credit never revives a payment staff marked failed by itself (the stock may have been sold since), but a
     * person can allocate it to that order, which is a decision that the customer did pay and brings the order back.
     */
    @Test
    void aCreditForAPaymentStaffMarkedFailedWaitsForAPersonWhoCanAllocateItToBringTheOrderBack() {
        paymentService.markFailed(payment.getId(), "no payment seen", "{\"type\":\"manual_staff_failure\"}");
        assertEquals(OrderStatus.PAYMENT_FAILED, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());

        BankCredit credit = creditService.record(uniq("TF"), new BigDecimal("500.00"), "Jane", null,
                "MPESA " + reference, Instant.now(), BankCredit.Source.MANUAL);

        assertEquals(BankCredit.CreditStatus.UNMATCHED, credit.getStatus());
        assertTrue(credit.getResolutionNote().contains("allocate this credit"), credit.getResolutionNote());
        assertEquals(OrderStatus.PAYMENT_FAILED, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());

        BankCredit allocated = creditService.allocate(credit.getId(), orderNo, "staff@example.com", "customer showed the SMS");

        assertEquals(BankCredit.CreditStatus.MATCHED, allocated.getStatus());
        assertEquals(OrderStatus.PAID, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
        assertEquals(PaymentRecord.PaymentStatus.SUCCESS,
                paymentRecordRepository.findById(payment.getId()).orElseThrow().getStatus());
    }

    /**
     * Found by the pre-push audit: a customer's claim waiting in the queue could be approved after staff had marked
     * the payment failed, quietly bringing the order back. Approving a claim does not revive a failed payment: staff are
     * told to use "Customer did pay" on the order, which is a deliberate step.
     */
    @Test
    void approvingAClaimOnAPaymentStaffMarkedFailedIsRefusedAndNothingIsRevived() {
        PaymentClaim claim = claimService.submit(orderNo, reference, uniq("QZ"), new BigDecimal("500"), null, "Jane");
        paymentService.markFailed(payment.getId(), "no payment seen", "{}");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> claimService.approve(claim.getId(), "staff@example.com", "looked fine"));

        assertTrue(refused.getMessage().contains("Customer did pay"), refused.getMessage());
        assertEquals(OrderStatus.PAYMENT_FAILED, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
        assertEquals(PaymentRecord.PaymentStatus.FAILED,
                paymentRecordRepository.findById(payment.getId()).orElseThrow().getStatus());
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
