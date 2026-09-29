package com.nbh.backend.tumaboda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.tumaboda.FakeTumaBodaServer.Recorded;
import com.nbh.backend.tumaboda.FakeTumaBodaServer.Reply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * TumaBoda end to end against {@link FakeTumaBodaServer}: the fee at checkout, booking a rider when the
 * order is marked READY (and what happens when that fails), the signed webhook moving the order along,
 * the reconciliation poll, the staff actions, and the public quote and address endpoints.
 *
 * <p>Real TumaBoda has not been called; see {@link TumaBodaClient}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TumaBodaIntegrationTest {

    private static final FakeTumaBodaServer FAKE = FakeTumaBodaServer.startUnchecked();
    private static final String SECRET = "whsec_integration_test_secret";
    private static final String CREATE = "/v1/partner/deliveries";
    private static final String QUOTE = "/v1/partner/deliveries/quote";
    private static final String BY_REFERENCE = "/v1/partner/deliveries/by-reference";
    private static final String QUOTE_OK =
            "{\"totalCostCents\":45000,\"serviceFeeCents\":5000,\"estimatedDistanceKm\":6.4,\"estimatedDurationMinutes\":22}";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @DynamicPropertySource
    static void tumaboda(DynamicPropertyRegistry registry) {
        registry.add("app.tumaboda.api-key", () -> "integration-api-key");
        registry.add("app.tumaboda.base-url", FAKE::baseUrl);
        registry.add("app.tumaboda.webhook-secret", () -> SECRET);
    }

    @AfterAll
    static void stopFake() {
        FAKE.close();
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TumaBodaBookingRetryJob retryJob;
    @Autowired private TumaBodaReconciliationJob reconcileJob;

    private String staffToken;
    private final AtomicInteger deliveries = new AtomicInteger();

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);

        FAKE.reset();
        FAKE.route("POST", QUOTE, Reply.ok(QUOTE_OK));
        // A booking answers with a fresh delivery each time, echoing the reference as the delivery number.
        FAKE.route("POST", CREATE, request -> {
            try {
                String reference = json.readTree(request.body()).get("reference").asText();
                return Reply.ok(delivery("tb-" + UUID.randomUUID(), "TB-" + reference, "pending", null));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ---- fake data ----

    private String delivery(String id, String number, String status, String riderId) {
        int n = deliveries.incrementAndGet();
        return """
                {"delivery":{"id":"%s","transactionId":"tx-%d","deliveryNumber":"%s","status":"%s",
                 "trackingCode":"TRK%d-%s","trackingUrl":"https://track.tumaboda.test/TRK%d","riderId":%s,
                 "totalCostCents":45000}}
                """.formatted(id, n, number, status, n, UUID.randomUUID().toString().substring(0, 4), n,
                riderId == null ? "null" : "\"" + riderId + "\"");
    }

    // ---- staff plumbing ----

    private JsonNode okStaff(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    private MvcResult staff(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        return mvc.perform(request).andReturn();
    }

    private int statusStaff(MockHttpServletRequestBuilder request) throws Exception {
        return staff(request).getResponse().getStatus();
    }

    private JsonNode detail(String orderId) throws Exception {
        return okStaff(get("/api/v1/orders/" + orderId));
    }

    // ---- shop plumbing ----

    private String newVariantWithStock(String price) throws Exception {
        String body = "{\"name\":\"TB Test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"" + price
                + "\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(JSON).content("{\"quantity\":10,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return variantId;
    }

    /** A cart holding {@code qty} of a fresh variant priced {@code price}; returns the cart session id. */
    private String cartWith(String price, int qty) throws Exception {
        String variantId = newVariantWithStock(price);
        String sessionId = UUID.randomUUID().toString();
        MvcResult added = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", sessionId)
                .contentType(JSON).content("{\"variantId\":\"" + variantId + "\",\"qty\":" + qty + "}")).andReturn();
        assertThat(added.getResponse().getStatus()).as(added.getResponse().getContentAsString()).isEqualTo(200);
        return sessionId;
    }

    private MvcResult checkoutRaw(String sessionId, String method, boolean dropoff) throws Exception {
        return checkoutRaw(sessionId, method, dropoff, "");
    }

    /** {@code extraJson} is appended inside the request object, e.g. {@code ,"expectedDeliveryFee":450}. */
    private MvcResult checkoutRaw(String sessionId, String method, boolean dropoff, String extraJson) throws Exception {
        String body = """
                {"customerName":"Wanjiku Mwangi","guestEmail":"%s","guestPhone":"%s",
                 "fulfilmentType":"DELIVERY_COURIER","deliveryAddress":"Kilimani, Wood Avenue, Nairobi",
                 "deliveryMethod":"%s"%s%s}
                """.formatted("t-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                "07" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)), method,
                dropoff ? ",\"dropoffLat\":-1.2921,\"dropoffLng\":36.8219" : "", extraJson);
        return mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", sessionId).contentType(JSON).content(body))
                .andReturn();
    }

    private record Placed(String orderId, String orderNo, String paymentId, JsonNode order) {}

    private Placed place(String price, String method, boolean dropoff) throws Exception {
        MvcResult result = checkoutRaw(cartWith(price, 1), method, dropoff);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode checkout = json.readTree(result.getResponse().getContentAsString());
        JsonNode order = checkout.get("order");
        return new Placed(order.get("id").asText(), order.get("orderNo").asText(), checkout.get("paymentRecordId").asText(), order);
    }

    private Placed placeTumaboda() throws Exception {
        return place("1000", "BODA", true);
    }

    private void pay(Placed placed) throws Exception {
        okStaff(post("/api/v1/payments/" + placed.paymentId() + "/confirm-manual").contentType(JSON)
                .content("{\"reference\":\"TESTCONFIRM" + UUID.randomUUID().toString().substring(0, 8)
                        + "\",\"method\":\"MPESA\"}"));
    }

    /** Placed, paid and marked READY — which is when a rider gets booked. */
    private Placed readyTumaboda() throws Exception {
        Placed placed = placeTumaboda();
        pay(placed);
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/mark-ready"));
        return placed;
    }

    private void backdate(String column, String orderId, String interval) {
        jdbc.update("UPDATE customer_order SET " + column + " = now() - interval '" + interval + "' WHERE id = ?",
                UUID.fromString(orderId));
    }

    // ---- webhook plumbing ----

    private static String signature(String secret, long timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        mac.update(body);
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
    }

    private String eventBody(String event, String deliveryId, String status, String riderId) {
        return "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"event\":\"" + event + "\",\"data\":{\"id\":\"" + deliveryId + "\""
                + (status == null ? "" : ",\"status\":\"" + status + "\"")
                + (riderId == null ? "" : ",\"riderId\":\"" + riderId + "\"") + "}}";
    }

    private int webhook(String body, String header) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/tumaboda/webhook").contentType(JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (header != null) {
            request.header("X-Tuma-Signature", header);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private int signedWebhook(String event, String deliveryId, String status, String riderId) throws Exception {
        String body = eventBody(event, deliveryId, status, riderId);
        return webhook(body, signature(SECRET, Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8)));
    }

    private String deliveryIdOf(Placed placed) throws Exception {
        return jdbc.queryForObject("SELECT tumaboda_delivery_id FROM customer_order WHERE id = ?", String.class,
                UUID.fromString(placed.orderId()));
    }

    // =====================================================================
    // Checkout
    // =====================================================================

    @Test
    void checkoutChargesTheQuotedFeeAndKeepsTheDropoffPoint() throws Exception {
        Placed placed = placeTumaboda();

        assertThat(placed.order().get("deliveryFee").decimalValue()).isEqualByComparingTo("450");
        assertThat(placed.order().get("grandTotal").decimalValue()).isEqualByComparingTo("1450");

        JsonNode quoteSent = json.readTree(FAKE.requestsTo("POST", QUOTE).get(0).body());
        assertThat(quoteSent.at("/dropoff/latitude").decimalValue()).isEqualByComparingTo("-1.2921");
        assertThat(quoteSent.at("/package/size").asText()).isEqualTo("small");
        assertThat(quoteSent.has("vehicleType")).isFalse();

        JsonNode detail = detail(placed.orderId());
        assertThat(detail.at("/tumaboda/booked").asBoolean()).isFalse();
        assertThat(detail.at("/tumaboda/rerouted").asBoolean()).isFalse();
        assertThat(detail.at("/tumaboda/customerPaid").decimalValue()).isEqualByComparingTo("450");
        assertThat(jdbc.queryForObject("SELECT dropoff_lat FROM customer_order WHERE id = ?", java.math.BigDecimal.class,
                UUID.fromString(placed.orderId()))).isEqualByComparingTo("-1.2921000");
    }

    @Test
    void thePaymentIsForTheProductsPlusTheQuotedFee() throws Exception {
        Placed placed = placeTumaboda();

        assertThat(jdbc.queryForObject("SELECT amount FROM payment_record WHERE id = ?", java.math.BigDecimal.class,
                UUID.fromString(placed.paymentId()))).isEqualByComparingTo("1450");
    }

    @Test
    void aBiggerBasketIsQuotedAsALargePackageAndAVanAtTheHighestTier() throws Exception {
        place("6000", "BODA", true);
        assertThat(json.readTree(FAKE.requestsTo("POST", QUOTE).get(0).body()).at("/package/size").asText())
                .isEqualTo("large");
        assertThat(json.readTree(FAKE.requestsTo("POST", QUOTE).get(0).body()).has("vehicleType")).isFalse();

        place("25000", "BODA", true);
        JsonNode van = json.readTree(FAKE.requestsTo("POST", QUOTE).get(1).body());
        assertThat(van.at("/package/size").asText()).isEqualTo("large");
        assertThat(van.get("vehicleType").asText()).isEqualTo("van");
    }

    @Test
    void aTumabodaOrderWithNoDropoffPointIsStillArrangedByPhone() throws Exception {
        Placed placed = place("1000", "BODA", false);

        assertThat(placed.order().get("deliveryFee").decimalValue()).isEqualByComparingTo("0");
        assertThat(FAKE.requestsTo("POST", QUOTE)).isEmpty();
        assertThat(detail(placed.orderId()).get("tumaboda").isNull()).isTrue();

        pay(placed);
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/assign-transporter").contentType(JSON)
                .content("{\"transporterName\":\"John Boda\",\"transporterPhone\":\"0733111222\",\"agreedPrice\":250}"));
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/mark-ready"));
        assertThat(FAKE.requestsTo("POST", CREATE)).as("nothing is booked for a phone-arranged order").isEmpty();
    }

    @Test
    void matatuAndCourierOrdersNeverTouchTumaboda() throws Exception {
        place("1000", "MATATU", true);
        place("1000", "COURIER", true);

        assertThat(FAKE.requests()).isEmpty();
    }

    @Test
    void aDropoffTumabodaCannotServeRefusesTheCheckoutAndLeavesTheCartAlone() throws Exception {
        String session = cartWith("1000", 1);
        FAKE.route("POST", QUOTE, Reply.error(400, "OUT_OF_COVERAGE", "we do not deliver there"));

        MvcResult refused = checkoutRaw(session, "BODA", true);

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("TumaBoda can't deliver to that address");
        assertThat(refused.getResponse().getContentAsString()).as("no upstream text leaks").doesNotContain("OUT_OF_COVERAGE");

        // The same basket checks out fine by matatu straight after.
        assertThat(checkoutRaw(session, "MATATU", false).getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * TumaBoda sets the delivery fee, and it is asked again while the order is placed, so it can differ
     * from the preview the shopper saw. The shopper is never charged a fee they were not shown: the order
     * is refused (409 PRICE_CHANGED), nothing is left behind, and the same basket then checks out at the
     * fee they now see.
     */
    @Test
    void aDeliveryFeeThatDiffersFromTheOneShownIsRefusedAndPlacesNothing() throws Exception {
        String session = cartWith("1000", 1);
        long ordersBefore = jdbc.queryForObject("SELECT count(*) FROM customer_order", Long.class);

        MvcResult refused = checkoutRaw(session, "BODA", true, ",\"expectedSubtotal\":1000,\"expectedDeliveryFee\":300");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(refused.getResponse().getContentAsString()).get("code").asText()).isEqualTo("PRICE_CHANGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Long.class)).as("no order left behind").isEqualTo(ordersBefore);

        MvcResult placed = checkoutRaw(session, "BODA", true, ",\"expectedSubtotal\":1000,\"expectedDeliveryFee\":450");
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json.readTree(placed.getResponse().getContentAsString()).get("order").get("deliveryFee").decimalValue())
                .isEqualByComparingTo("450");
    }

    @Test
    void aFailedQuoteRefusesTheCheckoutRatherThanChargingNothing() throws Exception {
        FAKE.route("POST", QUOTE, Reply.ok("{}"));

        assertThat(checkoutRaw(cartWith("1000", 1), "BODA", true).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void aDropoffOutsideKenyaIsRefusedWithoutAskingTumaboda() throws Exception {
        String session = cartWith("1000", 1);
        String body = """
                {"customerName":"Wanjiku Mwangi","guestEmail":"x-%s@example.com","guestPhone":"0711222333",
                 "fulfilmentType":"DELIVERY_COURIER","deliveryAddress":"London","deliveryMethod":"BODA",
                 "dropoffLat":51.5,"dropoffLng":-0.12}
                """.formatted(UUID.randomUUID().toString().substring(0, 8));

        MvcResult result = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(JSON).content(body)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(FAKE.requestsTo("POST", QUOTE)).isEmpty();
    }

    // =====================================================================
    // Booking
    // =====================================================================

    @Test
    void markingReadyBooksARiderAndRecordsTheDelivery() throws Exception {
        Placed placed = readyTumaboda();

        assertThat(FAKE.requestsTo("POST", CREATE)).hasSize(1);
        JsonNode sent = json.readTree(FAKE.requestsTo("POST", CREATE).get(0).body());
        assertThat(sent.get("reference").asText()).isEqualTo(placed.orderNo());
        assertThat(sent.at("/recipient/name").asText()).isEqualTo("Wanjiku Mwangi");
        assertThat(sent.at("/recipient/phone").asText()).matches("\\+2547\\d{8}");
        assertThat(sent.at("/recipient/location").asText()).isEqualTo("Kilimani, Wood Avenue, Nairobi");
        assertThat(sent.at("/recipient/latitude").asText()).isEqualTo("-1.2921000");
        assertThat(sent.at("/package/description").asText()).isEqualTo("Order " + placed.orderNo());
        assertThat(sent.at("/package/size").asText()).isEqualTo("small");
        assertThat(FAKE.requestsTo("POST", CREATE).get(0).apiKey()).isEqualTo("integration-api-key");

        JsonNode detail = detail(placed.orderId());
        assertThat(detail.get("status").asText()).isEqualTo("READY");
        assertThat(detail.at("/tumaboda/booked").asBoolean()).isTrue();
        assertThat(detail.at("/tumaboda/deliveryNumber").asText()).isEqualTo("TB-" + placed.orderNo());
        assertThat(detail.at("/tumaboda/status").asText()).isEqualTo("pending");
        assertThat(detail.at("/tumaboda/trackingUrl").asText()).startsWith("https://track.tumaboda.test/");
        assertThat(detail.at("/tumaboda/cost").decimalValue()).isEqualByComparingTo("450");
        assertThat(detail.at("/tumaboda/needsAttention").asBoolean()).isFalse();
        assertThat(detail.at("/tumaboda/bookingFailure").isNull()).isTrue();
    }

    @Test
    void anOrderIsNotBookedBeforeItIsReady() throws Exception {
        Placed placed = placeTumaboda();
        pay(placed);

        assertThat(FAKE.requestsTo("POST", CREATE)).isEmpty();
        assertThat(detail(placed.orderId()).at("/tumaboda/booked").asBoolean()).isFalse();
    }

    @Test
    void bookingTwiceIsRefusedAndNeverSendsASecondRider() throws Exception {
        Placed placed = readyTumaboda();

        assertThat(statusStaff(post("/api/v1/orders/" + placed.orderId() + "/tumaboda/book"))).isEqualTo(409);
        assertThat(FAKE.requestsTo("POST", CREATE)).hasSize(1);
    }

    @Test
    void aBookingThatFailsForNowIsRecordedAndRetriedLaterWithoutFailingTheStaffAction() throws Exception {
        FAKE.enqueue("POST", CREATE, Reply.error(503, "UNAVAILABLE", "try later"));
        Placed placed = placeTumaboda();
        pay(placed);

        okStaff(post("/api/v1/orders/" + placed.orderId() + "/mark-ready"));

        JsonNode failed = detail(placed.orderId());
        assertThat(failed.get("status").asText()).as("READY still went through").isEqualTo("READY");
        assertThat(failed.at("/tumaboda/booked").asBoolean()).isFalse();
        assertThat(failed.at("/tumaboda/bookingAttempts").asInt()).isEqualTo(1);
        assertThat(failed.at("/tumaboda/bookingFailure").asText()).isEqualTo("try later");
        assertThat(failed.at("/tumaboda/needsAttention").asBoolean()).as("it will retry by itself").isFalse();
        Instant next = Instant.parse(failed.at("/tumaboda/nextRetryAt").asText());
        assertThat(next).isBetween(Instant.now().plusSeconds(2 * 60), Instant.now().plusSeconds(4 * 60));

        // Not due yet: the job leaves it alone.
        assertThat(retryJob.retryDueBookings()).isZero();
        assertThat(FAKE.requestsTo("POST", CREATE)).hasSize(1);

        backdate("tumaboda_next_retry_at", placed.orderId(), "1 minute");
        assertThat(retryJob.retryDueBookings()).isEqualTo(1);

        JsonNode booked = detail(placed.orderId());
        assertThat(booked.at("/tumaboda/booked").asBoolean()).isTrue();
        assertThat(booked.at("/tumaboda/bookingFailure").isNull()).isTrue();
        assertThat(booked.at("/tumaboda/nextRetryAt").isNull()).isTrue();
        assertThat(FAKE.requestsTo("POST", CREATE)).hasSize(2);
        assertThat(json.readTree(FAKE.requestsTo("POST", CREATE).get(1).body()).get("reference").asText())
                .as("the retry uses the same reference, so it cannot become a second rider")
                .isEqualTo(placed.orderNo());
    }

    @Test
    void theRetryLadderEndsAfterFiveTriesAndThenStaffAreToldAboutIt() throws Exception {
        FAKE.route("POST", CREATE, Reply.error(503, "UNAVAILABLE", "still down"));
        Placed placed = placeTumaboda();
        pay(placed);
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/mark-ready"));

        for (int retry = 1; retry <= 4; retry++) {
            assertThat(detail(placed.orderId()).at("/tumaboda/needsAttention").asBoolean())
                    .as("still retrying after try %d", retry).isFalse();
            backdate("tumaboda_next_retry_at", placed.orderId(), "1 minute");
            assertThat(retryJob.retryDueBookings()).isEqualTo(1);
        }

        JsonNode given = detail(placed.orderId());
        assertThat(given.at("/tumaboda/bookingAttempts").asInt()).isEqualTo(5);
        assertThat(given.at("/tumaboda/nextRetryAt").isNull()).isTrue();
        assertThat(given.at("/tumaboda/needsAttention").asBoolean()).isTrue();
        assertThat(FAKE.requestsTo("POST", CREATE)).hasSize(5);
        assertThat(retryJob.retryDueBookings()).as("nothing more to try").isZero();
    }

    @Test
    void aBookingTumabodaRefusesIsNotRetriedButStaffCanBookAgainOnceItIsFixed() throws Exception {
        FAKE.enqueue("POST", CREATE, Reply.error(400, "INVALID_ADDRESS", "that address can't be served"));
        Placed placed = placeTumaboda();
        pay(placed);
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/mark-ready"));

        JsonNode refused = detail(placed.orderId());
        assertThat(refused.at("/tumaboda/nextRetryAt").isNull()).isTrue();
        assertThat(refused.at("/tumaboda/needsAttention").asBoolean()).isTrue();
        assertThat(refused.at("/tumaboda/bookingFailure").asText()).isEqualTo("that address can't be served");
        assertThat(retryJob.retryDueBookings()).isZero();

        JsonNode outcome = okStaff(post("/api/v1/orders/" + placed.orderId() + "/tumaboda/book"));

        assertThat(outcome.get("outcome").asText()).isEqualTo("BOOKED");
        JsonNode booked = detail(placed.orderId());
        assertThat(booked.at("/tumaboda/booked").asBoolean()).isTrue();
        assertThat(booked.at("/tumaboda/needsAttention").asBoolean()).isFalse();
    }

    @Test
    void a409FromTumabodaIsRecoveredIntoTheDeliveryItAlreadyHas() throws Exception {
        FAKE.enqueue("POST", CREATE, Reply.error(409, "DUPLICATE", "reference already used"));
        FAKE.route("GET", BY_REFERENCE, request -> Reply.ok(delivery("tb-already-there", "TB-EXISTING", "in_transit", "rider-3")));
        Placed placed = placeTumaboda();
        pay(placed);

        okStaff(post("/api/v1/orders/" + placed.orderId() + "/mark-ready"));

        JsonNode booked = detail(placed.orderId());
        assertThat(booked.at("/tumaboda/booked").asBoolean()).isTrue();
        assertThat(booked.at("/tumaboda/deliveryNumber").asText()).isEqualTo("TB-EXISTING");
        assertThat(booked.at("/tumaboda/riderId").asText()).isEqualTo("rider-3");
        assertThat(FAKE.requestsTo("GET", BY_REFERENCE).get(0).rawQuery()).isEqualTo("reference=" + placed.orderNo());
    }

    @Test
    void aRejectedApiKeyGoesStraightToStaffBecauseRetryingWillNotHelp() throws Exception {
        Placed placed = placeTumaboda();
        pay(placed);
        FAKE.route("POST", CREATE, Reply.error(401, "UNAUTHORIZED", "bad key"));

        okStaff(post("/api/v1/orders/" + placed.orderId() + "/mark-ready"));

        JsonNode detail = detail(placed.orderId());
        assertThat(detail.at("/tumaboda/needsAttention").asBoolean()).isTrue();
        assertThat(detail.at("/tumaboda/nextRetryAt").isNull()).as("a bad key won't fix itself").isTrue();
    }

    // =====================================================================
    // Webhook
    // =====================================================================

    @Test
    void aSignedInTransitReportDispatchesTheOrderAndRecordsTheRider() throws Exception {
        Placed placed = readyTumaboda();

        assertThat(signedWebhook("delivery.status_updated", deliveryIdOf(placed), "in_transit", "rider-7")).isEqualTo(200);

        JsonNode detail = detail(placed.orderId());
        assertThat(detail.get("status").asText()).isEqualTo("DISPATCHED");
        assertThat(detail.at("/tumaboda/status").asText()).isEqualTo("in_transit");
        assertThat(detail.at("/tumaboda/riderId").asText()).isEqualTo("rider-7");
    }

    @Test
    void aDeliveredReportCompletesTheOrder() throws Exception {
        Placed placed = readyTumaboda();
        String deliveryId = deliveryIdOf(placed);
        signedWebhook("delivery.status_updated", deliveryId, "in_transit", "rider-7");

        assertThat(signedWebhook("delivery.delivered", deliveryId, "delivered", null)).isEqualTo(200);

        JsonNode detail = detail(placed.orderId());
        assertThat(detail.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(detail.at("/tumaboda/status").asText()).isEqualTo("delivered");
    }

    @Test
    void deliveredStraightFromReadyStillDispatchesThenDelivers() throws Exception {
        Placed placed = readyTumaboda();

        signedWebhook("delivery.delivered", deliveryIdOf(placed), "delivered", null);

        assertThat(detail(placed.orderId()).get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void aDeliveredEventThatOnlyNamesItsStatusInTheEventNameIsStillUnderstood() throws Exception {
        Placed placed = readyTumaboda();

        signedWebhook("delivery.delivered", deliveryIdOf(placed), null, null);

        assertThat(detail(placed.orderId()).get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void anUnsignedOrBadlySignedReportChangesNothingAndIsRefused() throws Exception {
        Placed placed = readyTumaboda();
        String body = eventBody("delivery.status_updated", deliveryIdOf(placed), "delivered", null);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();

        assertThat(webhook(body, null)).as("no signature").isEqualTo(401);
        assertThat(webhook(body, "garbage")).as("garbage signature").isEqualTo(401);
        assertThat(webhook(body, signature("not-the-secret", now, bytes))).as("wrong secret").isEqualTo(401);
        assertThat(webhook(body, signature(SECRET, now - 3600, bytes))).as("an hour old").isEqualTo(401);
        assertThat(webhook(body.replace("delivered", "in_transit"), signature(SECRET, now, bytes)))
                .as("body changed after signing").isEqualTo(401);

        JsonNode detail = detail(placed.orderId());
        assertThat(detail.get("status").asText()).isEqualTo("READY");
        assertThat(detail.at("/tumaboda/status").asText()).isEqualTo("pending");
    }

    @Test
    void aStaleReportOlderThanWhatIsRecordedIsIgnored() throws Exception {
        Placed placed = readyTumaboda();
        String deliveryId = deliveryIdOf(placed);
        signedWebhook("delivery.status_updated", deliveryId, "in_transit", "rider-7");

        assertThat(signedWebhook("delivery.status_updated", deliveryId, "assigned", "rider-7")).isEqualTo(200);

        assertThat(detail(placed.orderId()).at("/tumaboda/status").asText()).isEqualTo("in_transit");
    }

    @Test
    void aReportForADeliveryWeDoNotHaveIsAcknowledgedAndIgnored() throws Exception {
        assertThat(signedWebhook("delivery.status_updated", "tb-nobody-knows", "delivered", null)).isEqualTo(200);
    }

    @Test
    void aReportWithNoDeliveryIdIsAcknowledgedAndIgnored() throws Exception {
        String body = "{\"id\":\"evt_x\",\"event\":\"delivery.status_updated\",\"data\":{\"status\":\"delivered\"}}";

        assertThat(webhook(body, signature(SECRET, Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(200);
    }

    @Test
    void aFailedDeliveryIsShownToStaffAndLeavesTheOrderWhereItWas() throws Exception {
        Placed placed = readyTumaboda();

        signedWebhook("delivery.status_updated", deliveryIdOf(placed), "failed", null);

        JsonNode detail = detail(placed.orderId());
        assertThat(detail.get("status").asText()).isEqualTo("READY");
        assertThat(detail.at("/tumaboda/status").asText()).isEqualTo("failed");
        assertThat(detail.at("/tumaboda/needsAttention").asBoolean()).isTrue();
    }

    @Test
    void afterAFailureStaffCanSendAFreshRiderUnderANewReference() throws Exception {
        Placed placed = readyTumaboda();
        String firstDelivery = deliveryIdOf(placed);
        signedWebhook("delivery.status_updated", firstDelivery, "failed", null);

        JsonNode outcome = okStaff(post("/api/v1/orders/" + placed.orderId() + "/tumaboda/restart"));

        assertThat(outcome.get("outcome").asText()).isEqualTo("BOOKED");
        assertThat(json.readTree(FAKE.requestsTo("POST", CREATE).get(1).body()).get("reference").asText())
                .isEqualTo(placed.orderNo() + "-R1");
        JsonNode detail = detail(placed.orderId());
        assertThat(detail.at("/tumaboda/status").asText()).isEqualTo("pending");
        assertThat(detail.at("/tumaboda/needsAttention").asBoolean()).isFalse();
        assertThat(deliveryIdOf(placed)).isNotEqualTo(firstDelivery);
        // The dead delivery's late reports no longer match this order.
        assertThat(signedWebhook("delivery.status_updated", firstDelivery, "in_transit", null)).isEqualTo(200);
        assertThat(detail(placed.orderId()).at("/tumaboda/status").asText()).isEqualTo("pending");
    }

    @Test
    void aDeliveryThatIsStillActiveCannotBeRestarted() throws Exception {
        Placed placed = readyTumaboda();

        assertThat(statusStaff(post("/api/v1/orders/" + placed.orderId() + "/tumaboda/restart"))).isEqualTo(409);
        assertThat(FAKE.requestsTo("POST", CREATE)).hasSize(1);
    }

    // =====================================================================
    // Reconciliation and "check now"
    // =====================================================================

    @Test
    void theReconciliationPollPicksUpWhatAMissedWebhookWouldHaveSaid() throws Exception {
        Placed placed = readyTumaboda();
        String deliveryId = deliveryIdOf(placed);
        FAKE.route("GET", "/v1/partner/deliveries/" + deliveryId, request ->
                Reply.ok(delivery(deliveryId, "TB-" + placed.orderNo(), "in_transit", "rider-9")));

        assertThat(reconcileJob.reconcile()).as("just booked, not quiet yet").isZero();

        backdate("tumaboda_last_webhook_at", placed.orderId(), "1 hour");
        assertThat(reconcileJob.reconcile()).isGreaterThanOrEqualTo(1);

        JsonNode detail = detail(placed.orderId());
        assertThat(detail.get("status").asText()).isEqualTo("DISPATCHED");
        assertThat(detail.at("/tumaboda/riderId").asText()).isEqualTo("rider-9");
    }

    @Test
    void checkNowAsksTumabodaAndAppliesTheAnswer() throws Exception {
        Placed placed = readyTumaboda();
        String deliveryId = deliveryIdOf(placed);
        FAKE.route("GET", "/v1/partner/deliveries/" + deliveryId, request ->
                Reply.ok(delivery(deliveryId, "TB-" + placed.orderNo(), "picked_up", "rider-2")));

        assertThat(statusStaff(post("/api/v1/orders/" + placed.orderId() + "/tumaboda/check"))).isEqualTo(204);

        JsonNode detail = detail(placed.orderId());
        assertThat(detail.get("status").asText()).isEqualTo("DISPATCHED");
        assertThat(detail.at("/tumaboda/status").asText()).isEqualTo("picked_up");
    }

    @Test
    void checkNowBeforeARiderIsBookedSaysSo() throws Exception {
        Placed placed = placeTumaboda();
        pay(placed);

        assertThat(statusStaff(post("/api/v1/orders/" + placed.orderId() + "/tumaboda/check"))).isEqualTo(409);
    }

    // =====================================================================
    // Guards on the order itself
    // =====================================================================

    @Test
    void anOrderCannotBeDispatchedByHandUntilARiderIsBooked() throws Exception {
        FAKE.route("POST", CREATE, Reply.error(400, "INVALID_ADDRESS", "no"));
        Placed placed = placeTumaboda();
        pay(placed);
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/mark-ready"));

        MvcResult refused = staff(post("/api/v1/orders/" + placed.orderId() + "/fulfil"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("hasn't been booked");
    }

    @Test
    void aTransporterCannotBeAssignedToAnOrderTumabodaCarries() throws Exception {
        Placed placed = readyTumaboda();

        assertThat(statusStaff(post("/api/v1/orders/" + placed.orderId() + "/assign-transporter").contentType(JSON)
                .content("{\"transporterName\":\"John Boda\",\"transporterPhone\":\"0733111222\",\"agreedPrice\":250}")))
                .isEqualTo(422);
    }

    @Test
    void anOrderWithARiderBookedCannotBeCancelledOrReroutedUntilTumabodaSaysItIsOver() throws Exception {
        Placed placed = readyTumaboda();

        assertThat(statusStaff(post("/api/v1/orders/" + placed.orderId() + "/cancel"))).isEqualTo(422);
        assertThat(statusStaff(post("/api/v1/orders/" + placed.orderId() + "/tumaboda/reroute-manual"))).isEqualTo(409);

        signedWebhook("delivery.status_updated", deliveryIdOf(placed), "cancelled", null);

        assertThat(statusStaff(post("/api/v1/orders/" + placed.orderId() + "/cancel"))).isEqualTo(200);
    }

    @Test
    void afterAnUnbookableOrderIsReroutedItRunsOnAPhoneArrangedTransporter() throws Exception {
        FAKE.route("POST", CREATE, Reply.error(400, "INVALID_ADDRESS", "no"));
        Placed placed = placeTumaboda();
        pay(placed);
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/mark-ready"));

        assertThat(statusStaff(post("/api/v1/orders/" + placed.orderId() + "/tumaboda/reroute-manual"))).isEqualTo(204);

        JsonNode rerouted = detail(placed.orderId());
        assertThat(rerouted.at("/tumaboda/rerouted").asBoolean()).isTrue();
        assertThat(rerouted.at("/tumaboda/needsAttention").asBoolean()).isFalse();
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/assign-transporter").contentType(JSON)
                .content("{\"transporterName\":\"John Boda\",\"transporterPhone\":\"0733111222\",\"agreedPrice\":250}"));
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/fulfil"));
        assertThat(detail(placed.orderId()).get("status").asText()).isEqualTo("DISPATCHED");
        assertThat(FAKE.requestsTo("POST", CREATE)).as("no further booking attempts").hasSize(1);
    }

    @Test
    void anOrderCancelledAfterAFailedBookingStopsBeingRetried() throws Exception {
        FAKE.route("POST", CREATE, Reply.error(503, "UNAVAILABLE", "down"));
        Placed placed = placeTumaboda();
        pay(placed);
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/mark-ready"));
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/cancel"));
        backdate("tumaboda_next_retry_at", placed.orderId(), "1 minute");

        retryJob.retryDueBookings();
        retryJob.retryDueBookings();

        assertThat(FAKE.requestsTo("POST", CREATE)).as("the one failed attempt, no more").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT tumaboda_next_retry_at FROM customer_order WHERE id = ?",
                java.sql.Timestamp.class, UUID.fromString(placed.orderId()))).as("no longer scheduled").isNull();
    }

    // =====================================================================
    // Public endpoints
    // =====================================================================

    private String caller() {
        return "test-caller-" + UUID.randomUUID();
    }

    @Test
    void theCheckoutCanAskWhetherTumabodaIsOn() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/public/tumaboda/status")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("enabled").asBoolean()).isTrue();
    }

    @Test
    void addressSuggestionsAreProxiedAndShortSearchesAreNotSent() throws Exception {
        FAKE.route("GET", "/v1/partner/maps/autocomplete", Reply.ok("""
                {"predictions":[{"place_id":"place-1","description":"Kilimani, Nairobi, Kenya"}]}
                """));

        MvcResult found = mvc.perform(get("/api/v1/public/tumaboda/maps/autocomplete").param("input", "Kilimani")
                .header("X-Forwarded-For", caller())).andReturn();
        MvcResult tooShort = mvc.perform(get("/api/v1/public/tumaboda/maps/autocomplete").param("input", "Ki")
                .header("X-Forwarded-For", caller())).andReturn();

        // Live leads; the seeded "Kilimani" area fills in behind it (2026-09-29: CommonDeliveryAreaService)
        // rather than being dropped — real matches from both sources are worth showing, not just live's.
        JsonNode suggestions = json.readTree(found.getResponse().getContentAsString());
        assertThat(suggestions.get(0).get("placeId").asText()).as("the live result leads").isEqualTo("place-1");
        assertThat(suggestions.get(0).get("description").asText()).isEqualTo("Kilimani, Nairobi, Kenya");
        assertThat(suggestions.findValuesAsText("description")).anyMatch(d -> d.startsWith("Kilimani, Nairobi"));
        assertThat(json.readTree(tooShort.getResponse().getContentAsString())).isEmpty();
        assertThat(FAKE.requestsTo("GET", "/v1/partner/maps/autocomplete")).hasSize(1);
    }

    @Test
    void whenTumabodaIsDownAddressSearchFallsBackToSeededAreasWithoutLeakingItsError() throws Exception {
        FAKE.route("GET", "/v1/partner/maps/autocomplete", Reply.error(500, "SECRET_INTERNAL_CODE", "stack trace here"));

        MvcResult result = mvc.perform(get("/api/v1/public/tumaboda/maps/autocomplete").param("input", "Kilimani")
                .header("X-Forwarded-For", caller())).andReturn();

        // 2026-09-29: a live failure no longer surfaces as an error at all — the seeded area list is
        // exactly the resilience this exists for, so the search box still answers with something real.
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SECRET_INTERNAL_CODE").doesNotContain("stack trace");
        JsonNode suggestions = json.readTree(result.getResponse().getContentAsString());
        assertThat(suggestions.findValuesAsText("description")).anyMatch(d -> d.startsWith("Kilimani"));
    }

    @Test
    void placeDetailsGivesTheCoordinatesAndRefusesAPlaceOutsideKenya() throws Exception {
        FAKE.route("GET", "/v1/partner/maps/place-details", request -> request.rawQuery().equals("place_id=abroad")
                ? Reply.ok("{\"result\":{\"geometry\":{\"location\":{\"lat\":51.5,\"lng\":-0.12}}}}")
                : Reply.ok("{\"result\":{\"geometry\":{\"location\":{\"lat\":-1.2921,\"lng\":36.8219}},\"formatted_address\":\"Kilimani, Nairobi\"}}"));

        MvcResult ok = mvc.perform(get("/api/v1/public/tumaboda/maps/place-details").param("placeId", "place-1")
                .header("X-Forwarded-For", caller())).andReturn();
        MvcResult abroad = mvc.perform(get("/api/v1/public/tumaboda/maps/place-details").param("placeId", "abroad")
                .header("X-Forwarded-For", caller())).andReturn();
        MvcResult junk = mvc.perform(get("/api/v1/public/tumaboda/maps/place-details").param("placeId", "bad id!")
                .header("X-Forwarded-For", caller())).andReturn();

        JsonNode details = json.readTree(ok.getResponse().getContentAsString());
        assertThat(details.get("latitude").decimalValue()).isEqualByComparingTo("-1.2921");
        assertThat(details.get("longitude").decimalValue()).isEqualByComparingTo("36.8219");
        assertThat(details.get("address").asText()).isEqualTo("Kilimani, Nairobi");
        assertThat(abroad.getResponse().getStatus()).isEqualTo(422);
        assertThat(junk.getResponse().getStatus()).isEqualTo(400);
        assertThat(FAKE.requestsTo("GET", "/v1/partner/maps/place-details")).as("junk is never forwarded").hasSize(2);
    }

    private MvcResult quote(String session, String body, String caller) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/public/tumaboda/quote").contentType(JSON).content(body)
                .header("X-Forwarded-For", caller);
        if (session != null) {
            request.header("X-Cart-Session", session);
        }
        return mvc.perform(request).andReturn();
    }

    @Test
    void aQuoteIsSizedByTheBasketAndReturnsTheFee() throws Exception {
        String session = cartWith("6000", 1);

        MvcResult result = quote(session, "{\"lat\":-1.2921,\"lng\":36.8219}", caller());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode quote = json.readTree(result.getResponse().getContentAsString());
        assertThat(quote.get("available").asBoolean()).isTrue();
        assertThat(quote.get("feeKes").decimalValue()).isEqualByComparingTo("450");
        assertThat(quote.get("distanceKm").decimalValue()).isEqualByComparingTo("6.4");
        assertThat(quote.get("durationMinutes").asInt()).isEqualTo(22);
        assertThat(json.readTree(FAKE.requestsTo("POST", QUOTE).get(0).body()).at("/package/size").asText())
                .as("the basket's 6,000 makes it a large package").isEqualTo("large");
    }

    @Test
    void aQuoteWithNoBasketStillWorks() throws Exception {
        MvcResult result = quote(null, "{\"lat\":-1.2921,\"lng\":36.8219}", caller());

        assertThat(json.readTree(result.getResponse().getContentAsString()).get("available").asBoolean()).isTrue();
        assertThat(json.readTree(FAKE.requestsTo("POST", QUOTE).get(0).body()).at("/package/size").asText()).isEqualTo("small");
    }

    @Test
    void aQuoteOutsideKenyaIsNotAskedOfTumaboda() throws Exception {
        MvcResult result = quote(null, "{\"lat\":51.5,\"lng\":-0.12}", caller());

        JsonNode quote = json.readTree(result.getResponse().getContentAsString());
        assertThat(quote.get("available").asBoolean()).isFalse();
        assertThat(quote.get("message").asText()).contains("Kenya");
        assertThat(FAKE.requestsTo("POST", QUOTE)).isEmpty();
    }

    @Test
    void aQuoteTumabodaRefusesTellsTheShopperWhatToDoInsteadOfFailing() throws Exception {
        FAKE.route("POST", QUOTE, Reply.error(400, "OUT_OF_COVERAGE", "raw upstream text"));
        JsonNode refused = json.readTree(quote(null, "{\"lat\":-1.29,\"lng\":36.82}", caller()).getResponse().getContentAsString());

        FAKE.route("POST", QUOTE, Reply.error(503, "UNAVAILABLE", "raw upstream text"));
        JsonNode down = json.readTree(quote(null, "{\"lat\":-1.29,\"lng\":36.82}", caller()).getResponse().getContentAsString());

        assertThat(refused.get("available").asBoolean()).isFalse();
        assertThat(refused.get("message").asText()).contains("can't deliver").doesNotContain("raw upstream");
        assertThat(down.get("available").asBoolean()).isFalse();
        assertThat(down.get("message").asText()).contains("try again").doesNotContain("raw upstream");
    }

    @Test
    void aQuoteRequestWithoutCoordinatesIsABadRequest() throws Exception {
        assertThat(quote(null, "{\"lat\":-1.29}", caller()).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void oneCallerCannotHammerTheQuoteEndpoint() throws Exception {
        String caller = caller();
        for (int i = 0; i < TumaBodaThrottle.QUOTES_PER_CALLER; i++) {
            assertThat(quote(null, "{\"lat\":-1.29,\"lng\":36.82}", caller).getResponse().getStatus()).isEqualTo(200);
        }

        assertThat(quote(null, "{\"lat\":-1.29,\"lng\":36.82}", caller).getResponse().getStatus()).isEqualTo(429);
        assertThat(quote(null, "{\"lat\":-1.29,\"lng\":36.82}", caller()).getResponse().getStatus())
                .as("another caller is unaffected").isEqualTo(200);
    }
}
