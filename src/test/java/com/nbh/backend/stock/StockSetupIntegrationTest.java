package com.nbh.backend.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Taking the shop live before every shelf is counted (owner, 2026-10-26, V78): an option with no stock number can be sold
 * without a limit when a "sell without stock" switch covers it (nothing is held for it, the order records the sale); many
 * options can be given a number at once, marked estimated, without touching counted ones; a stock take can cover a whole
 * category and makes what it counted "counted". Unique names per run so it can share the database with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockSetupIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.nbh.backend.order.service.OrderExpiryService orderExpiry;

    private String token;

    @BeforeEach
    void signIn() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    private record Item(String productId, String variantId) {}

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? json.createObjectNode() : json.readTree(content);
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private String newCategory() throws Exception {
        return ok(post("/api/v1/categories"), "{\"name\":\"" + unique("Stock shelf") + "\"}").get("id").asText();
    }

    /** A published product with one option and no stock number at all. */
    private Item newProduct(String categoryId) throws Exception {
        String body = "{\"name\":\"" + unique("Stock item") + "\",\"categoryIds\":[" + (categoryId == null ? "" : "\"" + categoryId + "\"")
                + "],\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"isActive\":true}]}";
        JsonNode product = ok(post("/api/v1/products/draft"), body);
        String productId = product.get("id").asText();
        ok(post("/api/v1/products/" + productId + "/publish"), null);
        return new Item(productId, product.get("variants").get(0).get("id").asText());
    }

    private void setStock(Item item, int qty) throws Exception {
        ok(put("/api/v1/products/" + item.productId() + "/variants/" + item.variantId() + "/stock"),
                "{\"quantity\":" + qty + ",\"unitCost\":100}");
    }

    private String basis(Item item) {
        return jdbc.queryForObject("SELECT stock_basis FROM product_variant WHERE id = ?::uuid", String.class, item.variantId());
    }

    private BigDecimal onHand(Item item) {
        BigDecimal qty = jdbc.query("SELECT on_hand_qty FROM stock_balance WHERE variant_id = ?::uuid AND account = 'SHOP'",
                rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO, item.variantId());
        return qty == null ? BigDecimal.ZERO : qty;
    }

    private JsonNode stockRow(Item item) throws Exception {
        return ok(get("/api/v1/stock/inventory?productId=" + item.productId()), null).get("content").get(0);
    }

    private int addToCart(String session, Item item, int qty) throws Exception {
        return mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + item.variantId() + "\",\"qty\":" + qty + "}")).andReturn().getResponse().getStatus();
    }

    @Test
    void anOptionWithNoNumberCanBeSoldWhenItsProductIsSwitchedToSellWithoutStockAndNothingIsHeld() throws Exception {
        Item item = newProduct(null);
        assertThat(basis(item)).isEqualTo("NONE");
        assertThat(addToCart(UUID.randomUUID().toString(), item, 1)).as("no stock, no switch").isNotEqualTo(200);

        ok(put("/api/v1/stock/setup/products/" + item.productId()), "{\"on\":true}");
        assertThat(stockRow(item).get("untracked").asBoolean()).isTrue();

        String session = UUID.randomUUID().toString();
        assertThat(addToCart(session, item, 5)).isEqualTo(200);
        String body = """
                {"customerName":"Akinyi Otieno","guestEmail":"untracked-%s@example.com","guestPhone":"07%08d","fulfilmentType":"PICKUP"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), ThreadLocalRandom.current().nextInt(100_000_000));
        MvcResult placed = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        // the sale is on the order, and no stock was held for it
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order_item WHERE variant_id = ?::uuid AND reservation_id IS NULL",
                Integer.class, item.variantId())).isEqualTo(1);
        assertThat(onHand(item)).isEqualByComparingTo("0");

        // with nothing held, the unpaid order lapses when its payment window does
        UUID orderId = jdbc.queryForObject("SELECT order_id FROM customer_order_item WHERE variant_id = ?::uuid",
                UUID.class, item.variantId());
        assertThat(orderExpiry.expireIfLapsed(orderId)).as("window still open").isFalse();
        jdbc.update("UPDATE payment_record SET expires_at = now() - interval '1 minute' WHERE order_id = ?", orderId);
        assertThat(orderExpiry.expireIfLapsed(orderId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class, orderId))
                .isEqualTo("EXPIRED");

        // a number for it makes it tracked like any other
        setStock(item, 3);
        assertThat(basis(item)).isEqualTo("COUNTED");
        assertThat(stockRow(item).get("untracked").asBoolean()).isFalse();
        assertThat(addToCart(UUID.randomUUID().toString(), item, 5)).as("only 3 now").isNotEqualTo(200);
    }

    @Test
    void aCategorysSwitchCoversItsProductsWithoutANumberAndNotTheCountedOnes() throws Exception {
        String category = newCategory();
        Item none = newProduct(category);
        Item counted = newProduct(category);
        setStock(counted, 0); // counted: there really is none

        JsonNode summary = ok(put("/api/v1/stock/setup/categories/" + category), "{\"on\":true}");
        assertThat(summary.get("categories").toString()).contains(category);
        assertThat(stockRow(none).get("untracked").asBoolean()).isTrue();
        assertThat(stockRow(counted).get("untracked").asBoolean()).as("a counted zero stays a zero").isFalse();
        assertThat(addToCart(UUID.randomUUID().toString(), none, 2)).isEqualTo(200);
        assertThat(addToCart(UUID.randomUUID().toString(), counted, 1)).isNotEqualTo(200);

        ok(put("/api/v1/stock/setup/categories/" + category), "{\"on\":false}");
        assertThat(stockRow(none).get("untracked").asBoolean()).isFalse();
    }

    @Test
    void setManyGivesOptionsWithoutANumberAnEstimateAndLeavesCountedOnesUnlessToldToOverwrite() throws Exception {
        String category = newCategory();
        Item none = newProduct(category);
        Item counted = newProduct(category);
        setStock(counted, 4);

        String request = "{\"categoryIds\":[\"" + category + "\"],\"qty\":20,\"overwrite\":%s,\"preview\":%s}";
        JsonNode preview = ok(post("/api/v1/stock/setup/set-many"), request.formatted(false, true));
        assertThat(preview.get("options").asInt()).isEqualTo(2);
        assertThat(preview.get("changed").asInt()).isEqualTo(1);
        assertThat(preview.get("skipped").asInt()).isEqualTo(1);
        assertThat(onHand(none)).as("a preview changes nothing").isEqualByComparingTo("0");

        ok(post("/api/v1/stock/setup/set-many"), request.formatted(false, false));
        assertThat(onHand(none)).isEqualByComparingTo("20");
        assertThat(basis(none)).isEqualTo("ESTIMATED");
        assertThat(onHand(counted)).isEqualByComparingTo("4");
        assertThat(basis(counted)).isEqualTo("COUNTED");

        ok(post("/api/v1/stock/setup/set-many"), request.formatted(true, false));
        assertThat(onHand(counted)).isEqualByComparingTo("20");
        assertThat(basis(counted)).isEqualTo("ESTIMATED");
    }

    @Test
    void aStockTakeOfACategoryCountsEveryOptionInItAndMakesThemCounted() throws Exception {
        String category = newCategory();
        Item first = newProduct(category);
        Item second = newProduct(category);
        ok(post("/api/v1/stock/setup/set-many"), "{\"categoryIds\":[\"" + category + "\"],\"qty\":20,\"overwrite\":false,\"preview\":false}");

        JsonNode count = ok(post("/api/v1/stock/counts/for-scope"), "{\"categoryIds\":[\"" + category + "\"],\"reason\":\"test\"}");
        assertThat(count.get("lines").size()).isEqualTo(2);
        JsonNode line = count.get("lines").get(0);
        assertThat(line.get("productId").asText()).isIn(first.productId(), second.productId());
        assertThat(line.get("categoryId").asText()).isEqualTo(category);
        String countId = count.get("id").asText();

        // both at once, as "set all its options to" does
        JsonNode recorded = ok(put("/api/v1/stock/counts/" + countId + "/lines"),
                "{\"counted\":{\"" + first.variantId() + "\":7,\"" + second.variantId() + "\":0}}");
        assertThat(recorded.get("lines").findValuesAsText("counted")).containsOnly("true");

        ok(post("/api/v1/stock/counts/" + countId + "/apply"), "{}");
        assertThat(onHand(first)).isEqualByComparingTo("7");
        assertThat(onHand(second)).isEqualByComparingTo("0");
        assertThat(basis(first)).isEqualTo("COUNTED");
        assertThat(basis(second)).isEqualTo("COUNTED");
    }

    @Test
    void theSummarySaysWhereTheShopStands() throws Exception {
        newProduct(null);
        JsonNode summary = ok(get("/api/v1/stock/setup"), null);
        assertThat(summary.get("withoutNumber").asLong()).isPositive();
        assertThat(summary.has("counted")).isTrue();
        assertThat(summary.has("estimated")).isTrue();
        assertThat(summary.get("sellWithoutStockEverywhere").isBoolean()).isTrue();
    }
}
