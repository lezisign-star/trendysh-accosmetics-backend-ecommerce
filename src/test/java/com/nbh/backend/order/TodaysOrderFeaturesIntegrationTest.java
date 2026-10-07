package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.repository.CustomerOrderRepository;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The order-side features added on 2026-10-02, exercised through the real HTTP layer: customer returns (at the
 * original sale cost), the TumaBoda settlement ledger, the delivery-contact phone, the list-level "needs
 * attention" flag, the real Customers screen and the daily trend endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TodaysOrderFeaturesIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CustomerOrderRepository orders;

    private String token;

    @BeforeEach
    void signIn() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    // ---- helpers ----

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + token);
        MvcResult r = mvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String body = r.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** A published variant with 10 units received at cost 100, selling at 1000. Returns {productId, variantId}. */
    private String[] variantWithStock() throws Exception {
        String body = "{\"name\":\"Today Test " + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = ok(jsonBody(post("/api/v1/products/draft"), body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        ok(jsonBody(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock"),
                "{\"quantity\":10,\"unitCost\":100}"));
        ok(post("/api/v1/products/" + productId + "/publish"));
        return new String[]{productId, variantId};
    }

    private record Placed(String orderId, String orderNo, String variantId, String phone) {}

    /** Real public checkout, then staff confirm the payment: the order lands PAID. */
    private Placed paidOrder(String fulfilmentType, String extraCheckoutFields) throws Exception {
        String variantId = variantWithStock()[1];
        String sessionId = UUID.randomUUID().toString();
        MvcResult cart = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + variantId + "\",\"qty\":1}")).andReturn();
        assertThat(cart.getResponse().getStatus()).isEqualTo(200);

        String phone = TestContacts.phone();
        String body = """
                {"customerName":"Today Tester","guestEmail":"%s","guestPhone":"%s","fulfilmentType":"%s"%s}
                """.formatted(TestContacts.email(), phone, fulfilmentType,
                extraCheckoutFields == null ? "" : "," + extraCheckoutFields);
        MvcResult checkout = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", sessionId)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(checkout.getResponse().getStatus()).as(checkout.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode c = json.readTree(checkout.getResponse().getContentAsString());
        String orderId = c.get("order").get("id").asText();
        String orderNo = c.get("order").get("orderNo").asText();
        ok(jsonBody(post("/api/v1/payments/" + c.get("paymentRecordId").asText() + "/confirm-manual"),
                "{\"reference\":\"TODAY" + UUID.randomUUID().toString().substring(0, 8) + "\",\"method\":\"MPESA\"}"));
        return new Placed(orderId, orderNo, variantId, phone);
    }

    private double shopOnHand(String variantId) throws Exception {
        JsonNode balances = ok(get("/api/v1/stock/balance").param("variantId", variantId));
        for (JsonNode b : balances) {
            if ("SHOP".equals(b.get("account").asText())) {
                return b.get("onHandQty").asDouble();
            }
        }
        return 0;
    }

    private JsonNode firstItem(String orderId) throws Exception {
        return ok(get("/api/v1/orders/" + orderId)).get("items").get(0);
    }

    // ---- customer returns ----

    @Test
    void aRestockedReturnPutsTheUnitBackAtItsOriginalCostAndCapsWhatCanComeBack() throws Exception {
        Placed p = paidOrder("PICKUP", null);
        ok(post("/api/v1/orders/" + p.orderId() + "/fulfil"));
        assertThat(shopOnHand(p.variantId())).isEqualTo(9.0);

        String itemId = firstItem(p.orderId()).get("itemId").asText();
        JsonNode after = ok(jsonBody(post("/api/v1/orders/" + p.orderId() + "/returns"),
                "{\"lines\":[{\"itemId\":\"" + itemId + "\",\"qty\":\"1\",\"restock\":true}],\"reason\":\"Changed mind\"}"));
        assertThat(after.get("items").get(0).get("returnedQty").asDouble()).isEqualTo(1.0);
        assertThat(shopOnHand(p.variantId())).isEqualTo(10.0);

        // Valued at what the unit originally cost (100), not whatever the average is now.
        JsonNode movements = ok(get("/api/v1/stock/movements").param("variantId", p.variantId()).param("size", "20"));
        boolean found = false;
        for (JsonNode m : movements.get("content")) {
            if ("CUSTOMER_RETURN".equals(m.get("documentType").asText()) && "SHOP".equals(m.get("account").asText())) {
                assertThat(m.get("unitCost").asDouble()).isEqualTo(100.0);
                found = true;
            }
        }
        assertThat(found).as("a CUSTOMER_RETURN entry on the shop account").isTrue();

        // Nothing left to return: a second attempt is refused, stock unchanged.
        assertThat(status(jsonBody(post("/api/v1/orders/" + p.orderId() + "/returns"),
                "{\"lines\":[{\"itemId\":\"" + itemId + "\",\"qty\":\"1\",\"restock\":true}]}"))).isEqualTo(422);
        assertThat(shopOnHand(p.variantId())).isEqualTo(10.0);
    }

    @Test
    void aDamagedReturnDoesNotGoBackOnTheShelf() throws Exception {
        Placed p = paidOrder("PICKUP", null);
        ok(post("/api/v1/orders/" + p.orderId() + "/fulfil"));
        String itemId = firstItem(p.orderId()).get("itemId").asText();

        ok(jsonBody(post("/api/v1/orders/" + p.orderId() + "/returns"),
                "{\"lines\":[{\"itemId\":\"" + itemId + "\",\"qty\":\"1\",\"restock\":false}]}"));

        assertThat(shopOnHand(p.variantId())).isEqualTo(9.0);
        assertThat(firstItem(p.orderId()).get("returnedQty").asDouble()).isEqualTo(1.0);
    }

    @Test
    void nothingCanBeReturnedBeforeTheOrderHasShipped() throws Exception {
        Placed p = paidOrder("PICKUP", null);
        String itemId = firstItem(p.orderId()).get("itemId").asText();
        assertThat(status(jsonBody(post("/api/v1/orders/" + p.orderId() + "/returns"),
                "{\"lines\":[{\"itemId\":\"" + itemId + "\",\"qty\":\"1\",\"restock\":true}]}"))).isEqualTo(422);
    }

    @Test
    void youCannotReturnMoreThanWasSold() throws Exception {
        Placed p = paidOrder("PICKUP", null);
        ok(post("/api/v1/orders/" + p.orderId() + "/fulfil"));
        String itemId = firstItem(p.orderId()).get("itemId").asText();
        assertThat(status(jsonBody(post("/api/v1/orders/" + p.orderId() + "/returns"),
                "{\"lines\":[{\"itemId\":\"" + itemId + "\",\"qty\":\"2\",\"restock\":true}]}"))).isEqualTo(422);
        assertThat(status(jsonBody(post("/api/v1/orders/" + p.orderId() + "/returns"),
                "{\"lines\":[{\"itemId\":\"" + itemId + "\",\"qty\":\"0\",\"restock\":true}]}"))).isEqualTo(422);
    }

    /** Found in the 2026-10-06 live QA: the refusal read "only 1.000 of 1.000 sold is still returnable (0.000 already returned)". */
    @Test
    void anOverReturnIsRefusedInPlainWordsWithTheProductNameAndWholeNumbers() throws Exception {
        Placed p = paidOrder("PICKUP", null);
        ok(post("/api/v1/orders/" + p.orderId() + "/fulfil"));
        String itemId = firstItem(p.orderId()).get("itemId").asText();
        String path = "/api/v1/orders/" + p.orderId() + "/returns";

        MvcResult tooMany = mvc.perform(jsonBody(post(path),
                "{\"lines\":[{\"itemId\":\"" + itemId + "\",\"qty\":\"5\",\"restock\":true}]}")
                .header("Authorization", "Bearer " + token)).andReturn();
        String message = tooMany.getResponse().getContentAsString();
        assertThat(message).contains("Today Test").contains("only 1 can still be returned")
                .contains("1 were sold, 0 already came back").doesNotContain("1.000").doesNotContain("0.000");

        ok(jsonBody(post(path), "{\"lines\":[{\"itemId\":\"" + itemId + "\",\"qty\":\"1\",\"restock\":true}]}"));
        MvcResult none = mvc.perform(jsonBody(post(path),
                "{\"lines\":[{\"itemId\":\"" + itemId + "\",\"qty\":\"1\",\"restock\":true}]}")
                .header("Authorization", "Bearer " + token)).andReturn();
        assertThat(none.getResponse().getContentAsString()).contains("all 1 sold on this order have already been returned");
    }

    // ---- delivery contact phone ----

    @Test
    void aDistinctDeliveryContactPhoneIsStoredNormalisedAndASamePhoneIsNot() throws Exception {
        Placed distinct = paidOrder("DELIVERY_COURIER",
                "\"deliveryAddress\":\"Kilimani\",\"deliveryMethod\":\"MATATU\",\"deliveryContactPhone\":\"0799888777\"");
        assertThat(ok(get("/api/v1/orders/" + distinct.orderId())).get("deliveryContactPhone").asText())
                .isEqualTo("254799888777");

        Placed same = new Placed(null, null, null, null);
        String variantId = variantWithStock()[1];
        String session = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":\"" + variantId + "\",\"qty\":1}"));
        String phone = TestContacts.phone();
        MvcResult res = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"Same Phone\",\"guestEmail\":\"" + TestContacts.email() + "\",\"guestPhone\":\""
                        + phone + "\",\"fulfilmentType\":\"DELIVERY_COURIER\",\"deliveryAddress\":\"Kilimani\","
                        + "\"deliveryMethod\":\"MATATU\",\"deliveryContactPhone\":\"" + phone + "\"}")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String id = json.readTree(res.getResponse().getContentAsString()).get("order").get("id").asText();
        assertThat(ok(get("/api/v1/orders/" + id)).get("deliveryContactPhone").isNull()).isTrue();
        assertThat(same.orderId()).isNull();
    }

    // ---- needs attention (list level) ----

    @Test
    void aDeadTumabodaDeliveryIsFlaggedInTheListAndCounted_andReroutingClearsIt() throws Exception {
        Placed p = paidOrder("PICKUP", null);
        long before = ok(get("/api/v1/orders/needs-attention-count")).get("count").asLong();

        CustomerOrder order = orders.findById(UUID.fromString(p.orderId())).orElseThrow();
        order.setTumabodaStatus("failed");
        orders.save(order);

        assertThat(ok(get("/api/v1/orders/needs-attention-count")).get("count").asLong()).isEqualTo(before + 1);
        JsonNode flagged = ok(get("/api/v1/orders").param("needsAttentionOnly", "true").param("q", p.orderNo()));
        assertThat(flagged.get("content")).hasSize(1);
        assertThat(flagged.get("content").get(0).get("needsAttention").asBoolean()).isTrue();

        order = orders.findById(UUID.fromString(p.orderId())).orElseThrow();
        order.setTumabodaRerouted(true);
        orders.save(order);
        assertThat(ok(get("/api/v1/orders").param("needsAttentionOnly", "true").param("q", p.orderNo()))
                .get("content")).isEmpty();
    }

    // ---- TumaBoda settlement ledger ----

    @Test
    void aSettlementSweepsEveryOutstandingCostedDeliveryAndOnlyThose() throws Exception {
        Placed costed = paidOrder("PICKUP", null);
        Placed uncosted = paidOrder("PICKUP", null);
        CustomerOrder order = orders.findById(UUID.fromString(costed.orderId())).orElseThrow();
        order.setTumabodaCost(new BigDecimal("450.00"));
        orders.save(order);

        JsonNode outstanding = ok(get("/api/v1/admin/tumaboda-settlement/outstanding"));
        assertThat(contains(outstanding.get("lines"), "orderNo", costed.orderNo())).isTrue();
        assertThat(contains(outstanding.get("lines"), "orderNo", uncosted.orderNo())).isFalse();
        assertThat(outstanding.get("totalOwed").asDouble()).isGreaterThanOrEqualTo(450.0);

        // The amount is what the bank shows, and it is what is owed: anything else is refused unless confirmed.
        String owed = outstanding.get("totalOwed").asText();
        JsonNode settlement = ok(jsonBody(post("/api/v1/admin/tumaboda-settlement"),
                "{\"settledAt\":\"2026-10-02\",\"amount\":" + owed + ",\"reference\":\"BANK-TEST\"}"));
        assertThat(settlement.get("orderCount").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode after = ok(get("/api/v1/admin/tumaboda-settlement/outstanding"));
        assertThat(contains(after.get("lines"), "orderNo", costed.orderNo())).isFalse();
        assertThat(after.get("totalOwed").asDouble()).isZero();
        JsonNode history = ok(get("/api/v1/admin/tumaboda-settlement/history"));
        assertThat(contains(history, "reference", "BANK-TEST")).isTrue();

        // A second settlement has nothing left to cover: refused, unless it is confirmed as an advance.
        assertThat(status(jsonBody(post("/api/v1/admin/tumaboda-settlement"),
                "{\"settledAt\":\"2026-10-02\",\"amount\":10}"))).isEqualTo(422);
        assertThat(ok(jsonBody(post("/api/v1/admin/tumaboda-settlement"),
                "{\"settledAt\":\"2026-10-02\",\"amount\":10,\"confirmDifference\":true}")).get("orderCount").asLong()).isZero();
    }

    /**
     * Found in the 2026-10-06 live QA: KSh 5,000 was accepted against KSh 900 owed and the difference vanished, because a
     * settlement clears everything owed whatever its amount. A different amount now needs an explicit confirmation.
     */
    @Test
    void aSettlementThatIsNotWhatIsOwedIsRefusedWithBothFiguresUnlessConfirmed() throws Exception {
        Placed costed = paidOrder("PICKUP", null);
        CustomerOrder order = orders.findById(UUID.fromString(costed.orderId())).orElseThrow();
        order.setTumabodaCost(new BigDecimal("450.00"));
        orders.save(order);
        String owed = ok(get("/api/v1/admin/tumaboda-settlement/outstanding")).get("totalOwed").asText();
        BigDecimal tooMuch = new BigDecimal(owed).add(new BigDecimal("4100"));

        MvcResult refused = mvc.perform(jsonBody(post("/api/v1/admin/tumaboda-settlement"),
                "{\"settledAt\":\"2026-10-02\",\"amount\":" + tooMuch + "}")
                .header("Authorization", "Bearer " + token)).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("You owe TumaBoda KSh").contains("record it anyway");
        assertThat(ok(get("/api/v1/admin/tumaboda-settlement/outstanding")).get("totalOwed").asText())
                .as("a refused settlement clears nothing").isEqualTo(owed);

        assertThat(ok(jsonBody(post("/api/v1/admin/tumaboda-settlement"),
                "{\"settledAt\":\"2026-10-02\",\"amount\":" + tooMuch + ",\"confirmDifference\":true}"))
                .get("orderCount").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aSettlementNeedsAPositiveAmountAndADate() throws Exception {
        assertThat(status(jsonBody(post("/api/v1/admin/tumaboda-settlement"),
                "{\"settledAt\":\"2026-10-02\",\"amount\":0}"))).isEqualTo(400);
        assertThat(status(jsonBody(post("/api/v1/admin/tumaboda-settlement"), "{\"amount\":10}"))).isEqualTo(400);
    }

    // ---- real Customers screen ----

    @Test
    void theCustomersScreenGroupsRealOrdersByPhoneAndCountsOnlyPaidSpend() throws Exception {
        Placed paid = paidOrder("PICKUP", null);

        JsonNode page = ok(get("/api/v1/admin/customers").param("q", paid.phone()));
        JsonNode row = null;
        for (JsonNode c : page.get("content")) {
            if (c.get("phone").asText().endsWith(paid.phone().substring(paid.phone().length() - 8))) row = c;
        }
        assertThat(row).as("the customer who just ordered").isNotNull();
        assertThat(row.get("orders").asLong()).isEqualTo(1);
        assertThat(row.get("spent").asDouble()).isEqualTo(1000.0);
        assertThat(row.get("name").asText()).isEqualTo("Today Tester");
    }

    // ---- daily trend ----

    @Test
    void theDailySeriesHasOnePointPerDayWithTodaysOrderInIt() throws Exception {
        paidOrder("PICKUP", null);
        JsonNode series = ok(get("/api/v1/admin/analytics/daily").param("days", "3"));
        assertThat(series).hasSize(3);
        JsonNode today = series.get(2);
        assertThat(today.get("orders").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(today.get("revenue").asDouble()).isGreaterThanOrEqualTo(1000.0);
        assertThat(series.get(0).get("date").asText()).isLessThan(today.get("date").asText());
    }

    private static boolean contains(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (n.has(field) && value.equals(n.get(field).asText())) return true;
        }
        return false;
    }
}
