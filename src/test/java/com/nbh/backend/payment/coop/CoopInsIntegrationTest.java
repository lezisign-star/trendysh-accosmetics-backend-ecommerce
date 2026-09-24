package com.nbh.backend.payment.coop;

import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.BankCreditRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Co-op INS receiver through the real security chain, using payloads shaped from the bank's PUBLISHED
 * INSSimulation schema. This proves our side conforms to that schema; it does NOT prove the real bank behaves the
 * same way (no sandbox credentials have been used).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.coop.ins.username=coop-test",
        "app.coop.ins.password=test-only-not-a-real-credential",
        "app.coop.ins.account-number=54321987654321",
        "app.coop.ins.auto-match=true",
})
class CoopInsIntegrationTest {

    private static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString("coop-test:test-only-not-a-real-credential".getBytes(StandardCharsets.UTF_8));

    @Autowired private MockMvc mvc;
    @Autowired private BranchRepository branchRepository;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private BankCreditRepository creditRepository;
    @Autowired private PaymentReferenceGenerator referenceGenerator;

    private String orderNo;
    private String reference;

    @BeforeEach
    void setUp() {
        orderNo = "COOPINS-" + UUID.randomUUID().toString().substring(0, 8);
        reference = referenceGenerator.generate();

        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(orderNo);
        order.setGuestEmail("payer@example.com");
        order.setGuestPhone("254700000000");
        order.setFulfilmentBranch(branchRepository.findByCode("BR-A").orElseThrow());
        order.setFulfilmentType(FulfilmentType.BRANCH_PICKUP);
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
        record.setPaymentReference(reference);
        record.setExpiresAt(Instant.now().plusSeconds(2700));
        paymentRecordRepository.save(record);
    }

    /** All 14 fields the published schema marks required, plus the optional ones. */
    private String payload(String txnId, String eventType, String account, String amount, String memo1) {
        return "{\"MessageReference\":\"93C4EEB126E056B6E05400144FF97BA9\","
                + "\"MessageDateTime\":\"2026-09-21T16:54:20\",\"ServiceName\":\"INS\",\"NotificationCode\":\"1\","
                + "\"PaymentRef\":\"SFI427E9136D7D3F21C2C89\",\"AccountNumber\":\"" + account + "\","
                + "\"Amount\":" + amount + ",\"TransactionDate\":\"20260921165420\","
                + "\"EventType\":\"" + eventType + "\",\"Currency\":\"KES\",\"ExchangeRate\":1,"
                + "\"Narration\":\"MPESA 400222\","
                + "\"CustMemo\":{\"CustMemoLine1\":\"" + memo1 + "\",\"CustMemoLine2\":\"\",\"CustMemoLine3\":\"\"},"
                + "\"ValueDate\":\"20260921\",\"EntryDate\":\"20260921\",\"TransactionId\":\"" + txnId + "\"}";
    }

    private MockHttpServletRequestBuilder ins(String auth, String body) {
        MockHttpServletRequestBuilder b = post("/api/v1/payments/coop/ins")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return auth == null ? b : b.header("Authorization", auth);
    }

    private String txn() {
        return "CT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void missingOrWrongCredentialsAreRefusedAndRecordNothing() throws Exception {
        String t = txn();
        mvc.perform(ins(null, payload(t, "CREDIT", "54321987654321", "500", reference))).andExpect(status().isUnauthorized());
        mvc.perform(ins("Basic " + Base64.getEncoder().encodeToString("coop-test:wrong".getBytes(StandardCharsets.UTF_8)),
                payload(t, "CREDIT", "54321987654321", "500", reference))).andExpect(status().isUnauthorized());
        mvc.perform(ins("Bearer abc", payload(t, "CREDIT", "54321987654321", "500", reference))).andExpect(status().isUnauthorized());
        assertTrue(creditRepository.findByBankTxnId(t).isEmpty());
    }

    @Test
    void aCreditWithOurReferenceAndTheExactAmountPaysTheOrderWhenAutoMatchIsOn() throws Exception {
        String t = txn();
        mvc.perform(ins(BASIC, payload(t, "CREDIT", "54321987654321", "500.00", reference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.MessageCode").value("0"))
                .andExpect(jsonPath("$.MessageReference").value("93C4EEB126E056B6E05400144FF97BA9"));

        assertEquals(OrderStatus.PAID, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
        assertEquals("MATCHED", creditRepository.findByBankTxnId(t).orElseThrow().getStatus().name());
    }

    @Test
    void aDuplicateNotificationIsAcknowledgedAndChangesNothing() throws Exception {
        String t = txn();
        String body = payload(t, "CREDIT", "54321987654321", "500.00", reference);
        mvc.perform(ins(BASIC, body)).andExpect(status().isOk());
        mvc.perform(ins(BASIC, body)).andExpect(status().isOk());
        assertEquals(1, creditRepository.findAll().stream().filter(c -> c.getBankTxnId().equals(t)).count());
    }

    @Test
    void debitsAndOtherAccountsAreIgnoredButAcknowledged() throws Exception {
        String debit = txn();
        String other = txn();
        mvc.perform(ins(BASIC, payload(debit, "DEBIT", "54321987654321", "500", reference))).andExpect(status().isOk());
        mvc.perform(ins(BASIC, payload(other, "CREDIT", "99999999999999", "500", reference))).andExpect(status().isOk());
        assertTrue(creditRepository.findByBankTxnId(debit).isEmpty());
        assertTrue(creditRepository.findByBankTxnId(other).isEmpty());
        assertEquals(OrderStatus.PENDING_PAYMENT, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
    }

    @Test
    void aWrongAmountIsRecordedButLeftForStaff() throws Exception {
        String t = txn();
        mvc.perform(ins(BASIC, payload(t, "CREDIT", "54321987654321", "300", reference))).andExpect(status().isOk());
        assertEquals("UNMATCHED", creditRepository.findByBankTxnId(t).orElseThrow().getStatus().name());
        assertEquals(OrderStatus.PENDING_PAYMENT, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
    }

    @Test
    void anUnreadableBodyIsAcknowledgedNotRetriedForever() throws Exception {
        mvc.perform(ins(BASIC, "this is not json")).andExpect(status().isOk());
    }
}
