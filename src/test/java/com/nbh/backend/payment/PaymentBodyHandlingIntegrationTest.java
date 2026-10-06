package com.nbh.backend.payment;

import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import com.nbh.backend.staff.repository.StaffUserRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Found in the 2026-10-06 go-live QA: a staff request to confirm (or fail) a payment with a body the server
 * could not read was answered 200 {"ResultCode":"0","ResultDesc":"Accepted"} and did nothing, so the dashboard
 * would have told staff the payment was confirmed while the order stayed unpaid. That answer belongs to Safaricom's
 * callback alone (it retries anything else); a staff call with a bad body is a 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentBodyHandlingIntegrationTest {

    private static final String CALLBACK_SECRET = "test-only-callback-secret-not-for-any-real-deployment";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private PaymentReferenceGenerator referenceGenerator;

    private String staffToken;
    private String orderNo;
    private UUID paymentId;

    @BeforeEach
    void setUp() {
        staffToken = jwtService.generateAccessToken(
                staffUsers.findByEmailIgnoreCaseAndDeletedFalse("admin@nairobibeautyhub.co.ke").orElseThrow());
        orderNo = "BODY-" + UUID.randomUUID().toString().substring(0, 8);

        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(orderNo);
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
        record.setPaymentReference(referenceGenerator.generate());
        record.setExpiresAt(Instant.now().plusSeconds(2700));
        paymentId = paymentRecordRepository.save(record).getId();
    }

    private PaymentRecord.PaymentStatus paymentStatus() {
        return paymentRecordRepository.findById(paymentId).orElseThrow().getStatus();
    }

    @Test
    void confirmingWithAnUnreadableBodyIsABadRequestNotAFalseAccepted() throws Exception {
        mvc.perform(post("/api/v1/payments/" + paymentId + "/confirm-manual")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ResultDesc").doesNotExist());

        assertThat(paymentStatus()).isEqualTo(PaymentRecord.PaymentStatus.INITIATED);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void confirmingWithAMethodThatDoesNotExistIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/payments/" + paymentId + "/confirm-manual")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"BODY-" + UUID.randomUUID() + "\",\"notes\":\"x\",\"method\":\"BITCOIN\"}"))
                .andExpect(status().isBadRequest());

        assertThat(paymentStatus()).isEqualTo(PaymentRecord.PaymentStatus.INITIATED);
    }

    @Test
    void failingWithAnUnreadableBodyIsABadRequestAndLeavesThePaymentAlone() throws Exception {
        mvc.perform(post("/api/v1/payments/" + paymentId + "/fail-manual")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("[1, 2,"))
                .andExpect(status().isBadRequest());

        assertThat(paymentStatus()).isEqualTo(PaymentRecord.PaymentStatus.INITIATED);
    }

    @Test
    void aProperConfirmationStillPaysTheOrder() throws Exception {
        mvc.perform(post("/api/v1/payments/" + paymentId + "/confirm-manual")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        // The provider reference is unique across all payments, like a real bank or M-PESA code.
                        .content("{\"reference\":\"BODY-" + UUID.randomUUID() + "\",\"notes\":\"seen on the statement\",\"method\":\"BANK\"}"))
                .andExpect(status().isOk());

        assertThat(paymentStatus()).isEqualTo(PaymentRecord.PaymentStatus.SUCCESS);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void safaricomsCallbackIsStillAcknowledgedWhateverTheBodyLooksLike() throws Exception {
        // It retries any other answer, so a body we cannot read is logged and answered "Accepted".
        for (String body : new String[] {"{ this is not json", "", "[]", "{\"Body\":{\"stkCallback\":{}}}"}) {
            mvc.perform(post("/api/v1/payments/mpesa/callback/" + CALLBACK_SECRET)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ResultCode").value("0"))
                    .andExpect(jsonPath("$.ResultDesc").value("Accepted"));
        }
        assertThat(paymentStatus()).isEqualTo(PaymentRecord.PaymentStatus.INITIATED);
    }

    @Test
    void aCallbackWithoutTheRightSecretIsNotAnswered() throws Exception {
        mvc.perform(post("/api/v1/payments/mpesa/callback/not-the-secret")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
