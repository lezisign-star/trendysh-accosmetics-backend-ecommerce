package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The order tabs by payment code (2026-10-10): orders that have a code entered (or are already paid), orders still
 * waiting for one, and orders that expired without one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderPaymentCodeTabsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private PaymentRecordRepository payments;

    private String staffToken;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    private JsonNode okStaff(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private JsonNode placeOrder() throws Exception {
        String name = "Tabs Test " + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        String session = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + variantId + "\",\"qty\":1}")).andReturn();
        String checkout = """
                {"customerName":"Tabs Customer","guestEmail":"%s","guestPhone":"%s","fulfilmentType":"PICKUP",
                 "deliveryAddress":"Junction"}
                """.formatted(TestContacts.email(), TestContacts.phone());
        MvcResult result = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content(checkout)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private boolean tabHas(String paymentCode, String orderNo) throws Exception {
        JsonNode page = okStaff(get("/api/v1/orders").param("paymentCode", paymentCode).param("q", orderNo));
        for (JsonNode o : page.get("content")) {
            if (orderNo.equals(o.get("orderNo").asText())) return true;
        }
        return false;
    }

    @Test
    void anOrderMovesFromWaitingToCodeReceivedWhenTheCustomerEntersACode() throws Exception {
        JsonNode checkout = placeOrder();
        String orderNo = checkout.get("order").get("orderNo").asText();
        UUID paymentId = UUID.fromString(checkout.get("paymentRecordId").asText());

        assertThat(tabHas("NO_CODE", orderNo)).isTrue();
        assertThat(tabHas("WITH_CODE", orderNo)).isFalse();
        assertThat(tabHas("EXPIRED_NO_CODE", orderNo)).isFalse();

        String reference = payments.findById(paymentId).orElseThrow().getPaymentReference();
        MvcResult claim = mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionCode\":\"TB" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
                        + "\",\"amount\":1000}")).andReturn();
        assertThat(claim.getResponse().getStatus()).as(claim.getResponse().getContentAsString()).isEqualTo(200);

        assertThat(tabHas("NO_CODE", orderNo)).isFalse();
        assertThat(tabHas("WITH_CODE", orderNo)).isTrue();
    }

    @Test
    void anUnknownTabIsRefused() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/orders").param("paymentCode", "SOMETHING")
                .header("Authorization", "Bearer " + staffToken)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }
}
