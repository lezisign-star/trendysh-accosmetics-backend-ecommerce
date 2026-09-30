package com.nbh.backend.cart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.cart.service.CartService;
import com.nbh.backend.common.security.JwtService;
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

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The price a shopper sees in their basket, and is charged at checkout, is TODAY's price. It used to be
 * the one remembered from the moment the item went in, so a deal that had ended, or a price an admin had
 * corrected, was still charged for as long as the item sat there. Also: a shopper is never charged a total
 * different from the one they were shown (409 PRICE_CHANGED), and a product that can no longer be bought
 * cannot be checked out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CartRepricingIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CartService cartService;

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
        return body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    private record Item(String productId, String variantId) {}

    private Item newVariant(String price) throws Exception {
        return newVariant(price, 10);
    }

    private Item newVariant(String price, int stockQty) throws Exception {
        String body = "{\"name\":\"Reprice " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"" + price
                + "\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + stockQty + ",\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return new Item(productId, variantId);
    }

    private void setWholesale(Item item, int minQty, String wholesalePrice) throws Exception {
        okStaff(put("/api/v1/products/" + item.productId() + "/variants/" + item.variantId() + "/wholesale")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"minQty\":" + minQty + ",\"wholesalePrice\":\"" + wholesalePrice + "\"}"));
    }

    private void clearWholesale(Item item) throws Exception {
        okStaff(delete("/api/v1/products/" + item.productId() + "/variants/" + item.variantId() + "/wholesale"));
    }

    private JsonNode updateQty(String session, String itemId, int qty) throws Exception {
        MvcResult result = mvc.perform(put("/api/v1/cart/items/" + itemId).header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":" + qty + "}")).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode addToCart(String session, Item item, int qty) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + item.variantId() + "\",\"qty\":" + qty + "}")).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readCart(String session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/cart").header("X-Cart-Session", session)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void setPrice(Item item, String price) {
        jdbc.update("UPDATE product_variant SET selling_price = ?::numeric WHERE id = ?", price, UUID.fromString(item.variantId()));
    }

    private MvcResult checkout(String session, String extraJson) throws Exception {
        String body = """
                {"customerName":"Wanjiku Mwangi","guestEmail":"t-%s@example.com","guestPhone":"07%08d",
                 "fulfilmentType":"PICKUP"%s}
                """.formatted(UUID.randomUUID().toString().substring(0, 8),
                ThreadLocalRandom.current().nextInt(100_000_000), extraJson);
        return mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    void aPriceThatMovedWhileTheItemSatInTheBasketIsShownAtItsNewValueAndReportedOnce() throws Exception {
        Item item = newVariant("1000");
        String session = UUID.randomUUID().toString();
        JsonNode added = addToCart(session, item, 2);
        assertThat(added.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(added.get("items").get(0).get("previousUnitPrice").isNull()).isTrue();

        setPrice(item, "1200");
        JsonNode first = readCart(session);

        JsonNode line = first.get("items").get(0);
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("1200");
        assertThat(line.get("lineTotal").decimalValue()).isEqualByComparingTo("2400");
        assertThat(line.get("previousUnitPrice").decimalValue()).as("told once").isEqualByComparingTo("1000");
        assertThat(first.get("subtotal").decimalValue()).isEqualByComparingTo("2400");

        JsonNode second = readCart(session);
        assertThat(second.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("1200");
        assertThat(second.get("items").get(0).get("previousUnitPrice").isNull()).as("not repeated").isTrue();
    }

    @Test
    void aDealThatHasEndedStopsBeingChargedAndTheFullPriceReturns() throws Exception {
        Item item = newVariant("1000");
        okStaff(put("/api/v1/products/" + item.productId() + "/variants/" + item.variantId() + "/deal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dealPrice\":\"700\",\"dealEndsAt\":\"" + java.time.Instant.now().plusSeconds(3600) + "\"}"));
        String session = UUID.randomUUID().toString();
        JsonNode added = addToCart(session, item, 1);
        assertThat(added.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("700");

        jdbc.update("UPDATE product_variant SET deal_ends_at = now() - interval '1 minute' WHERE id = ?", UUID.fromString(item.variantId()));
        JsonNode after = readCart(session);

        assertThat(after.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(after.get("items").get(0).get("previousUnitPrice").decimalValue()).isEqualByComparingTo("700");
    }

    @Test
    void checkoutChargesTodaysPriceNotTheOneRememberedFromAddingTheItem() throws Exception {
        Item item = newVariant("1000");
        String session = UUID.randomUUID().toString();
        addToCart(session, item, 1);
        setPrice(item, "1300");

        MvcResult placed = checkout(session, "");

        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode order = json.readTree(placed.getResponse().getContentAsString()).get("order");
        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("1300");
        assertThat(order.get("grandTotal").decimalValue()).isEqualByComparingTo("1300");
    }

    @Test
    void checkoutRefusesWhenTheTotalTheShopperSawIsNoLongerTheRealOneAndPlacesNothing() throws Exception {
        Item item = newVariant("1000");
        String session = UUID.randomUUID().toString();
        addToCart(session, item, 1);
        setPrice(item, "1100"); // moved after the shopper last looked at the basket
        long ordersBefore = jdbc.queryForObject("SELECT count(*) FROM customer_order", Long.class);

        MvcResult refused = checkout(session, ",\"expectedSubtotal\":1000");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = json.readTree(refused.getResponse().getContentAsString());
        assertThat(error.get("code").asText()).isEqualTo("PRICE_CHANGED");
        assertThat(error.get("error").asText()).contains("prices changed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Long.class))
                .as("no order was left behind").isEqualTo(ordersBefore);

        // The basket now shows the new price, and placing the order knowingly at that total works.
        assertThat(readCart(session).get("subtotal").decimalValue()).isEqualByComparingTo("1100");
        MvcResult placed = checkout(session, ",\"expectedSubtotal\":1100");
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
    }

    @Test
    void checkoutIsAcceptedWhenTheShownTotalStillMatchesAndWhenNoneIsSent() throws Exception {
        Item item = newVariant("1000");
        String withTotal = UUID.randomUUID().toString();
        addToCart(withTotal, item, 3);
        String withoutTotal = UUID.randomUUID().toString();
        addToCart(withoutTotal, item, 1);

        assertThat(checkout(withTotal, ",\"expectedSubtotal\":3000.00,\"expectedDeliveryFee\":0").getResponse().getStatus()).isEqualTo(200);
        assertThat(checkout(withoutTotal, "").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aShownDeliveryFeeThatDoesNotMatchTheRealOneIsRefused() throws Exception {
        Item item = newVariant("1000");
        String session = UUID.randomUUID().toString();
        addToCart(session, item, 1);

        // Pickup has no delivery fee, so a shopper who was shown one is being shown a stale total.
        MvcResult refused = checkout(session, ",\"expectedSubtotal\":1000,\"expectedDeliveryFee\":250");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(refused.getResponse().getContentAsString()).get("code").asText()).isEqualTo("PRICE_CHANGED");
    }

    @Test
    void aProductThatCanNoLongerBeBoughtIsMarkedUnavailableAndBlocksCheckout() throws Exception {
        Item item = newVariant("1000");
        String session = UUID.randomUUID().toString();
        addToCart(session, item, 1);
        okStaff(post("/api/v1/products/" + item.productId() + "/unpublish"));

        JsonNode cart = readCart(session);
        MvcResult refused = checkout(session, "");

        assertThat(cart.get("items").get(0).get("available").asBoolean()).isFalse();
        assertThat(cart.get("items").get(0).get("unitPrice").decimalValue()).as("keeps its last price").isEqualByComparingTo("1000");
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("no longer available");
    }

    @Test
    void theBasketValueTheDeliveryQuoteIsSizedByUsesTodaysPrices() throws Exception {
        Item item = newVariant("1000");
        String session = UUID.randomUUID().toString();
        addToCart(session, item, 2);
        setPrice(item, "4000");

        assertThat(cartService.basketValue(session)).isEqualByComparingTo(new BigDecimal("8000"));
    }

    @Test
    void addingBelowTheWholesaleThresholdKeepsThePlainPrice() throws Exception {
        Item item = newVariant("1000", 20);
        setWholesale(item, 10, "700");
        String session = UUID.randomUUID().toString();

        JsonNode added = addToCart(session, item, 9);

        JsonNode line = added.get("items").get(0);
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(line.get("wholesaleApplied").asBoolean()).isFalse();
        assertThat(line.get("wholesaleMinQty").asInt()).isEqualTo(10);
        assertThat(line.get("wholesalePrice").decimalValue()).isEqualByComparingTo("700");
    }

    @Test
    void addingEnoughToCrossTheWholesaleThresholdChargesTheWholeLineAtTheWholesalePrice() throws Exception {
        Item item = newVariant("1000", 20);
        setWholesale(item, 10, "700");
        String session = UUID.randomUUID().toString();
        addToCart(session, item, 4);

        JsonNode added = addToCart(session, item, 6); // 4 + 6 = 10, crosses the threshold

        JsonNode line = added.get("items").get(0);
        assertThat(line.get("qty").decimalValue()).isEqualByComparingTo("10");
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("700");
        assertThat(line.get("lineTotal").decimalValue()).isEqualByComparingTo("7000");
        assertThat(line.get("wholesaleApplied").asBoolean()).isTrue();
    }

    @Test
    void updatingQtyAcrossTheWholesaleThresholdInOneCallRepricesTheLine() throws Exception {
        Item item = newVariant("1000", 20);
        setWholesale(item, 10, "700");
        String session = UUID.randomUUID().toString();
        JsonNode added = addToCart(session, item, 3);
        String itemId = added.get("items").get(0).get("id").asText();

        JsonNode updated = updateQty(session, itemId, 12);

        JsonNode line = updated.get("items").get(0);
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("700");
        assertThat(line.get("wholesaleApplied").asBoolean()).isTrue();
        // The shopper's own qty change, not a price that moved under them — no "was/now" notice.
        assertThat(line.get("previousUnitPrice").isNull()).isTrue();
    }

    @Test
    void droppingBackBelowTheThresholdRepricesUpAgain() throws Exception {
        Item item = newVariant("1000", 20);
        setWholesale(item, 10, "700");
        String session = UUID.randomUUID().toString();
        JsonNode added = addToCart(session, item, 12);
        String itemId = added.get("items").get(0).get("id").asText();
        assertThat(added.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("700");

        JsonNode updated = updateQty(session, itemId, 5);

        JsonNode line = updated.get("items").get(0);
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(line.get("wholesaleApplied").asBoolean()).isFalse();
        assertThat(line.get("previousUnitPrice").isNull()).isTrue();
    }

    @Test
    void theCheaperOfAnActiveDealAndTheWholesalePriceIsCharged() throws Exception {
        Item item = newVariant("1000", 20);
        setWholesale(item, 10, "700");
        // A deal at 900 is more expensive than the 700 wholesale price once qty qualifies.
        okStaff(put("/api/v1/products/" + item.productId() + "/variants/" + item.variantId() + "/deal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dealPrice\":\"900\",\"dealEndsAt\":\"" + java.time.Instant.now().plusSeconds(3600) + "\"}"));
        String session = UUID.randomUUID().toString();

        JsonNode added = addToCart(session, item, 10);

        JsonNode line = added.get("items").get(0);
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("700");
        assertThat(line.get("wholesaleApplied").asBoolean()).isTrue();
    }

    @Test
    void aDealCheaperThanWholesaleIsChargedInsteadOfWholesale() throws Exception {
        Item item = newVariant("1000", 20);
        setWholesale(item, 10, "700");
        // A deal at 600 beats the 700 wholesale price.
        okStaff(put("/api/v1/products/" + item.productId() + "/variants/" + item.variantId() + "/deal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dealPrice\":\"600\",\"dealEndsAt\":\"" + java.time.Instant.now().plusSeconds(3600) + "\"}"));
        String session = UUID.randomUUID().toString();

        JsonNode added = addToCart(session, item, 10);

        JsonNode line = added.get("items").get(0);
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("600");
        assertThat(line.get("wholesaleApplied").asBoolean()).isFalse();
    }

    @Test
    void checkoutOnAWholesaleQualifyingLineSnapshotsWholesaleAppliedOnTheOrderItem() throws Exception {
        Item item = newVariant("1000", 20);
        setWholesale(item, 10, "700");
        String session = UUID.randomUUID().toString();
        addToCart(session, item, 10);

        MvcResult placed = checkout(session, "");

        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode order = json.readTree(placed.getResponse().getContentAsString()).get("order");
        JsonNode orderLine = order.get("items").get(0);
        assertThat(orderLine.get("unitPrice").decimalValue()).isEqualByComparingTo("700");
        assertThat(orderLine.get("wholesaleApplied").asBoolean()).isTrue();
        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("7000");

        // Staff see it on the order detail too, so a line cheaper than the list price explains itself.
        JsonNode detail = okStaff(get("/api/v1/orders/" + order.get("id").asText()));
        assertThat(detail.get("items").get(0).get("wholesaleApplied").asBoolean()).isTrue();
    }

    @Test
    void clearingTheWholesaleTierRepricesAnActiveCartBackDownOnNextRead() throws Exception {
        Item item = newVariant("1000", 20);
        setWholesale(item, 10, "700");
        String session = UUID.randomUUID().toString();
        JsonNode added = addToCart(session, item, 10);
        assertThat(added.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("700");

        clearWholesale(item);
        JsonNode after = readCart(session);

        JsonNode line = after.get("items").get(0);
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(line.get("wholesaleApplied").asBoolean()).isFalse();
        assertThat(line.get("wholesaleMinQty").isNull()).isTrue();
    }
}
