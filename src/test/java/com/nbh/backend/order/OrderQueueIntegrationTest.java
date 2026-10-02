package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.common.util.Sha256;
import com.nbh.backend.order.entity.OrderTrackingSession;
import com.nbh.backend.order.repository.OrderTrackingSessionRepository;
import com.nbh.backend.order.service.OrderUrgency;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The fulfilment queue (oldest first, handled orders hidden, red urgency for yesterday's and overnight
 * orders) and the customer closing a dispatched order from the track page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderQueueIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private OrderTrackingSessionRepository sessions;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void signIn() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    // ---- urgency rule (pure) ----

    private static Instant nairobi(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("Africa/Nairobi")).toInstant();
    }

    @Test
    void anOrderIsUrgentWhenPlacedBeforeTodayOrBetween8pmAnd6amNairobiTime() {
        Instant now = nairobi(2026, 10, 2, 12, 0);
        assertThat(OrderUrgency.reason(nairobi(2026, 10, 2, 9, 30), now)).isNull();
        assertThat(OrderUrgency.reason(nairobi(2026, 10, 2, 6, 0), now)).as("6:00 is morning").isNull();
        assertThat(OrderUrgency.reason(nairobi(2026, 10, 2, 5, 59), now)).isEqualTo("Placed overnight");
        assertThat(OrderUrgency.reason(nairobi(2026, 10, 2, 0, 5), now)).isEqualTo("Placed overnight");
        assertThat(OrderUrgency.reason(nairobi(2026, 10, 2, 19, 59), nairobi(2026, 10, 2, 20, 30))).isNull();
        assertThat(OrderUrgency.reason(nairobi(2026, 10, 2, 20, 0), nairobi(2026, 10, 2, 20, 30))).isEqualTo("Placed overnight");
        assertThat(OrderUrgency.reason(nairobi(2026, 10, 1, 14, 0), now)).isEqualTo("From yesterday");
        assertThat(OrderUrgency.reason(nairobi(2026, 9, 20, 9, 0), now)).isEqualTo("From yesterday");
        // The day boundary is Nairobi's, not UTC's: 23:30 UTC on the 1st is 02:30 on the 2nd in Nairobi.
        assertThat(OrderUrgency.reason(Instant.parse("2026-10-01T23:30:00Z"), now)).isEqualTo("Placed overnight");
    }

    // ---- helpers ----

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + token);
        MvcResult r = mvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String body = r.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String variantWithStock() throws Exception {
        String body = "{\"name\":\"Queue Test " + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = ok(jsonBody(post("/api/v1/products/draft"), body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        ok(jsonBody(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock"),
                "{\"quantity\":10,\"unitCost\":100}"));
        ok(post("/api/v1/products/" + productId + "/publish"));
        return variantId;
    }

    private record Placed(String orderId, String orderNo, String email) {}

    /** Real public checkout (pickup), then staff confirm the payment: the order lands PAID. */
    private Placed paidOrder() throws Exception {
        String variantId = variantWithStock();
        String sessionId = UUID.randomUUID().toString();
        MvcResult cart = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + variantId + "\",\"qty\":1}")).andReturn();
        assertThat(cart.getResponse().getStatus()).isEqualTo(200);

        String email = TestContacts.email();
        String body = """
                {"customerName":"Queue Tester","guestEmail":"%s","guestPhone":"%s","fulfilmentType":"PICKUP"}
                """.formatted(email, TestContacts.phone());
        MvcResult checkout = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", sessionId)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(checkout.getResponse().getStatus()).as(checkout.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode c = json.readTree(checkout.getResponse().getContentAsString());
        ok(jsonBody(post("/api/v1/payments/" + c.get("paymentRecordId").asText() + "/confirm-manual"),
                "{\"reference\":\"Q" + UUID.randomUUID().toString().substring(0, 8) + "\",\"method\":\"MPESA\"}"));
        return new Placed(c.get("order").get("id").asText(), c.get("order").get("orderNo").asText(), email);
    }

    private JsonNode queue() throws Exception {
        return ok(get("/api/v1/orders").param("queue", "true").param("size", "200")).get("content");
    }


    private void dispatch(Placed order) throws Exception {
        ok(post("/api/v1/orders/" + order.orderId() + "/mark-ready"));
        ok(post("/api/v1/orders/" + order.orderId() + "/fulfil"));
    }

    private String trackToken(String email) {
        String raw = "q-" + UUID.randomUUID();
        OrderTrackingSession session = new OrderTrackingSession();
        session.setEmail(email.toLowerCase());
        session.setTokenHash(Sha256.hex(raw));
        session.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
        sessions.save(session);
        return raw;
    }

    // ---- queue ----

    @Test
    void theQueueListsOldestFirstFlagsYesterdayRedAndDropsHandledOrders() throws Exception {
        Placed older = paidOrder();
        Placed newer = paidOrder();
        // Make "older" two days old; the other keeps its real (just now) time.
        jdbc.update("UPDATE customer_order SET created_at = now() - interval '2 days' WHERE id = ?::uuid", older.orderId());

        // Oldest first across the whole page (the test database keeps orders from earlier runs, so this
        // checks the ordering rule itself rather than where these two particular orders land).
        JsonNode rows = queue();
        Instant previous = Instant.MIN;
        for (JsonNode row : rows) {
            Instant at = Instant.parse(row.get("createdAt").asText());
            assertThat(at).as("queue is oldest first").isAfterOrEqualTo(previous);
            previous = at;
        }

        JsonNode olderRow = ok(get("/api/v1/orders").param("queue", "true").param("q", older.orderNo())).get("content").get(0);
        assertThat(olderRow.get("orderNo").asText()).isEqualTo(older.orderNo());
        assertThat(olderRow.get("urgent").asBoolean()).isTrue();
        assertThat(olderRow.get("urgentReason").asText()).isEqualTo("From yesterday");
        assertThat(ok(get("/api/v1/orders").param("queue", "true").param("q", newer.orderNo())).get("content")).hasSize(1);

        dispatch(older);
        assertThat(ok(get("/api/v1/orders").param("queue", "true").param("q", older.orderNo())).get("content"))
                .as("dispatched orders are handled").isEmpty();
        assertThat(ok(get("/api/v1/orders").param("queue", "true").param("q", newer.orderNo())).get("content"))
                .as("the other order is still waiting").hasSize(1);

        // The ordinary list (no queue flag) still shows the handled order, with urgency on its row.
        JsonNode all = ok(get("/api/v1/orders").param("q", older.orderNo())).get("content");
        assertThat(all).hasSize(1);
        assertThat(all.get(0).get("urgent").asBoolean()).isTrue();
    }

    @Test
    void aStatusFilterInTheQueueNeverWidensItToHandledOrders() throws Exception {
        Placed order = paidOrder();
        dispatch(order);
        JsonNode rows = ok(get("/api/v1/orders").param("queue", "true").param("status", "DISPATCHED")).get("content");
        assertThat(rows).isEmpty();
    }

    // ---- customer closes a dispatched order ----

    @Test
    void theCustomerCanConfirmReceiptOfTheirOwnDispatchedOrderAndTwiceIsHarmless() throws Exception {
        Placed order = paidOrder();
        String t = trackToken(order.email());
        String url = "/api/v1/orders/track/email/orders/" + order.orderNo() + "/confirm-received";

        // Not dispatched yet: refused.
        assertThat(mvc.perform(post(url).header("X-Track-Token", t)).andReturn().getResponse().getStatus()).isEqualTo(422);

        dispatch(order);
        MvcResult done = mvc.perform(post(url).header("X-Track-Token", t)).andReturn();
        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json.readTree(done.getResponse().getContentAsString()).get("status").asText()).isEqualTo("DELIVERED");

        MvcResult again = mvc.perform(post(url).header("X-Track-Token", t)).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(again.getResponse().getContentAsString()).get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void onlyTheVerifiedEmailsOwnOrderCanBeConfirmed() throws Exception {
        Placed order = paidOrder();
        dispatch(order);
        String url = "/api/v1/orders/track/email/orders/" + order.orderNo() + "/confirm-received";

        String someoneElse = trackToken(TestContacts.email());
        assertThat(mvc.perform(post(url).header("X-Track-Token", someoneElse)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post(url)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post(url).header("X-Track-Token", "not-a-real-token")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ---- reviews from the track page, read by staff ----

    @Test
    void aCustomerCanReviewTheirDeliveredOrderOnceAndStaffSeeItPending() throws Exception {
        Placed order = paidOrder();
        dispatch(order);
        String t = trackToken(order.email());
        MvcResult done = mvc.perform(post("/api/v1/orders/track/email/orders/" + order.orderNo() + "/confirm-received")
                .header("X-Track-Token", t)).andReturn();
        String productId = json.readTree(done.getResponse().getContentAsString())
                .get("items").get(0).get("productId").asText();

        String url = "/api/v1/orders/track/email/orders/" + order.orderNo() + "/reviews";
        String body = "{\"productId\":\"" + productId + "\",\"rating\":5,\"comment\":\"Lovely and fast\",\"reviewerName\":\"Queue Tester\"}";
        assertThat(mvc.perform(post(url).header("X-Track-Token", t).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        // once per product per order
        assertThat(mvc.perform(post(url).header("X-Track-Token", t).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
        // someone else's session can't review it
        assertThat(mvc.perform(post(url).header("X-Track-Token", trackToken(TestContacts.email()))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus()).isEqualTo(404);
        // rating bounds
        assertThat(mvc.perform(post(url).header("X-Track-Token", t).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"rating\":5", "\"rating\":9"))).andReturn().getResponse().getStatus()).isEqualTo(400);

        JsonNode pending = ok(get("/api/v1/admin/reviews").param("status", "PENDING").param("size", "100")).get("content");
        boolean found = false;
        for (JsonNode r : pending) {
            if (order.orderNo().equals(r.get("orderNo").asText())) {
                found = true;
                assertThat(r.get("rating").asInt()).isEqualTo(5);
                assertThat(r.get("comment").asText()).isEqualTo("Lovely and fast");
                assertThat(r.get("productName").asText()).startsWith("Queue Test");
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void staffCanListReviewsByStatus() throws Exception {
        JsonNode page = ok(get("/api/v1/admin/reviews").param("status", "PENDING"));
        assertThat(page.get("content").isArray()).isTrue();
    }
}
