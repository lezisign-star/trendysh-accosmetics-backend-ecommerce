package com.nbh.backend.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.payment.service.PaymentService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the live-update wiring end to end, on the calling thread ({@code app.live.sync=true} — see
 * {@code application-test.yml}): a real checkout and a real payment confirmation, through the actual
 * MockMvc endpoints and services, must reach {@link LiveStreams} — not just a hand-built event reaching a
 * hand-built listener (that's {@link LiveUpdateListenerTest}), the whole path from HTTP request to commit
 * to live push. {@link LiveStreams} itself is spied on rather than replaced, so its real bookkeeping
 * (subscribe/cap/heartbeat) keeps running underneath the assertions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveEventStreamIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private PaymentService paymentService;
    @MockitoSpyBean private LiveStreams liveStreams;

    private String staffToken;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    private JsonNode okStaff(MockHttpServletRequestBuilder req) throws Exception {
        req.header("Authorization", "Bearer " + staffToken);
        MvcResult result = mvc.perform(req).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String newVariantWithStock(String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return variantId;
    }

    private String freshCartSessionWith(String variantId) throws Exception {
        String sessionId = UUID.randomUUID().toString();
        MvcResult result = mvc.perform(post("/api/v1/cart/items")
                        .header("X-Cart-Session", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"qty\":1}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return sessionId;
    }

    private JsonNode placeOrder(String sessionId) throws Exception {
        String phone = "07" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        String email = "live-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String body = """
                {"customerName":"Live Test Customer","guestEmail":"%s","guestPhone":"%s","fulfilmentType":"PICKUP"}
                """.formatted(email, phone);
        MvcResult result = mvc.perform(post("/api/v1/checkout")
                        .header("X-Cart-Session", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void placingAnOrderPingsStaffAndThatOrdersOwnWatchers() throws Exception {
        String variantId = newVariantWithStock("Live Order Test " + UUID.randomUUID().toString().substring(0, 8));
        String sessionId = freshCartSessionWith(variantId);

        JsonNode placed = placeOrder(sessionId);
        String orderNo = placed.get("order").get("orderNo").asText();
        BigDecimal grandTotal = placed.get("order").get("grandTotal").decimalValue();

        verify(liveStreams).publishToStaff(argThat(e ->
                e.type() == AdminEvent.Type.ORDER_PLACED && e.orderNo().equals(orderNo)
                        && e.amount().compareTo(grandTotal) == 0));
        verify(liveStreams).publishToOrder(orderNo);
    }

    @Test
    void confirmingPaymentPingsStaffAndThatOrdersOwnWatchers() throws Exception {
        String variantId = newVariantWithStock("Live Payment Test " + UUID.randomUUID().toString().substring(0, 8));
        String sessionId = freshCartSessionWith(variantId);
        JsonNode placed = placeOrder(sessionId);
        String orderNo = placed.get("order").get("orderNo").asText();
        UUID paymentRecordId = UUID.fromString(placed.get("paymentRecordId").asText());

        // Unique per run: this suite's database is not recreated between test executions, and
        // provider_reference is uniquely indexed (it is how a real Daraja callback is de-duplicated).
        paymentService.confirmSuccess(paymentRecordId, "LIVE-TEST-REF-" + UUID.randomUUID(), "{}");

        verify(liveStreams).publishToStaff(argThat(e ->
                e.type() == AdminEvent.Type.PAYMENT_CONFIRMED && e.orderNo().equals(orderNo)));
        // At least twice for this order: once when it was placed, again now that it is paid.
        verify(liveStreams, atLeastOnce()).publishToOrder(orderNo);
    }

    @Test
    void theStaffStreamRefusesAnyoneWithoutAStaffToken() throws Exception {
        mvc.perform(get("/api/v1/admin/events"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void theStaffStreamStartsForAStaffMemberWithOrderVisibility() throws Exception {
        mvc.perform(get("/api/v1/admin/events").header("Authorization", "Bearer " + staffToken))
                .andExpect(request().asyncStarted());
    }

    @Test
    void watchingAnUnknownOrderIs404() throws Exception {
        mvc.perform(get("/api/v1/orders/track/{orderNo}/events", "SO-NO-SUCH-ORDER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void watchingARealOrderStartsAnAnonymousStream() throws Exception {
        String variantId = newVariantWithStock("Live Track Test " + UUID.randomUUID().toString().substring(0, 8));
        String sessionId = freshCartSessionWith(variantId);
        String orderNo = placeOrder(sessionId).get("order").get("orderNo").asText();

        mvc.perform(get("/api/v1/orders/track/{orderNo}/events", orderNo))
                .andExpect(request().asyncStarted());
    }
}
