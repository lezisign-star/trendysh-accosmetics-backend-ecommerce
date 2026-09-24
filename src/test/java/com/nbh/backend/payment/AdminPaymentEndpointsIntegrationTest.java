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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The staff payments endpoints through the real security chain: who may call them and what they do. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminPaymentEndpointsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private PaymentReferenceGenerator referenceGenerator;

    private String orderNo;
    private String reference;
    private String staffToken;

    @BeforeEach
    void setUp() {
        staffToken = jwtService.generateAccessToken(
                staffUsers.findByEmailIgnoreCaseAndDeletedFalse("admin@nairobibeautyhub.co.ke").orElseThrow());
        orderNo = "ADMPAY-" + UUID.randomUUID().toString().substring(0, 8);
        reference = referenceGenerator.generate();

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
        record.setPaymentReference(reference);
        record.setExpiresAt(Instant.now().plusSeconds(2700));
        paymentRecordRepository.save(record);
    }

    @Test
    void endpointsRequireAStaffTokenAndNoTokenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/admin/payments/claims")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/payments/credits").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void claimThenApproveThroughTheApiPaysTheOrder() throws Exception {
        String code = "QAPI" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionCode\":\"" + code + "\",\"amount\":500}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/payments/awaiting").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.orderNo=='" + orderNo + "')].underReview").value(true));

        String claims = mvc.perform(get("/api/v1/admin/payments/claims").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.transactionCode=='" + code + "')].orderNo").value(orderNo))
                .andReturn().getResponse().getContentAsString();
        String claimId = com.jayway.jsonpath.JsonPath.<java.util.List<String>>read(
                claims, "$[?(@.transactionCode=='" + code + "')].id").get(0);

        mvc.perform(post("/api/v1/admin/payments/claims/" + claimId + "/approve")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"seen on statement\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertEquals(OrderStatus.PAID, orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus());
        mvc.perform(get("/api/v1/payments/pay/" + orderNo).param("ref", reference))
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void recordingACreditThroughTheApiMatchesOnReferenceAndAmount() throws Exception {
        mvc.perform(post("/api/v1/admin/payments/credits").header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankTxnId\":\"API-" + UUID.randomUUID() + "\",\"amount\":500,"
                                + "\"narration\":\"Transfer " + reference + "\",\"receivedAt\":\""
                                + Instant.now() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.matchedOrderNo").value(orderNo));
    }
}
