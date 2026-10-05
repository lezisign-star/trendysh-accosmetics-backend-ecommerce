package com.nbh.backend.cart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Demo-catalogue products (the DEVELOPER-only "demo data" toggle) must never be bought: an order for one would
 * take a real payment for something that does not exist. REAL products must stay buyable throughout, including
 * while demo mode is on, because real shoppers keep arriving on a live site while the owner is showing it off.
 *
 * <p>Demo mode is "on" whenever any demo product is published, so flagging one published product as demo
 * (the way {@code DemoDataIsolationIntegrationTest} does) puts the whole shop in that state for the test; every
 * flag is lifted again afterwards so other tests see the shop as they expect.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoProductsNotForSaleIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;

    private String staffToken;
    private final List<String> flaggedAsDemo = new ArrayList<>();

    @BeforeEach
    void signIn() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    @AfterEach
    void liftEveryDemoFlagSoOtherTestsSeeARealShop() {
        for (String productId : flaggedAsDemo) {
            jdbc.update("UPDATE product SET is_mock_data = false WHERE id = ?", UUID.fromString(productId));
        }
    }

    private record Item(String productId, String variantId, String name) {}

    private JsonNode okStaff(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    private Item newPublishedProduct(String label) throws Exception {
        String name = label + " " + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return new Item(productId, variantId, name);
    }

    private void makeItDemo(Item item) {
        jdbc.update("UPDATE product SET is_mock_data = true WHERE id = ?", UUID.fromString(item.productId()));
        flaggedAsDemo.add(item.productId());
    }

    private MvcResult tryAdd(String session, Item item, int qty) throws Exception {
        return mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + item.variantId() + "\",\"qty\":" + qty + "}")).andReturn();
    }

    private void add(String session, Item item) throws Exception {
        MvcResult result = tryAdd(session, item, 1);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }

    private JsonNode readCart(String session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/cart").header("X-Cart-Session", session)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult checkout(String session) throws Exception {
        String body = """
                {"customerName":"Wanjiku Mwangi","guestEmail":"demo-%s@example.com","guestPhone":"07%08d","fulfilmentType":"PICKUP"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), ThreadLocalRandom.current().nextInt(100_000_000));
        return mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    void aDemoProductCannotBePutInABasketAndTheShopperIsToldWhy() throws Exception {
        Item demo = newPublishedProduct("Demo Gel Polish");
        makeItDemo(demo);

        MvcResult refused = tryAdd(UUID.randomUUID().toString(), demo, 1);

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains(demo.name() + " is a demo product and can't be ordered");
    }

    @Test
    void aDemoProductAlreadyInABasketIsMarkedUnavailableAndBlocksCheckout() throws Exception {
        Item item = newPublishedProduct("Became Demo");
        String session = UUID.randomUUID().toString();
        add(session, item); // it was a real product when it went in
        makeItDemo(item);

        JsonNode cart = readCart(session);
        MvcResult refused = checkout(session);

        assertThat(cart.get("items").get(0).get("available").asBoolean()).isFalse();
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("is a demo product and can't be ordered");
    }

    @Test
    void oneDemoLineStopsTheWholeCheckoutAndNoOrderIsCreated() throws Exception {
        Item real = newPublishedProduct("Real Polish");
        Item soonDemo = newPublishedProduct("Soon Demo");
        String session = UUID.randomUUID().toString();
        add(session, real);
        add(session, soonDemo);
        makeItDemo(soonDemo);
        long ordersBefore = jdbc.queryForObject("SELECT count(*) FROM customer_order", Long.class);

        MvcResult refused = checkout(session);

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Long.class))
                .as("nothing was ordered, not even the real line").isEqualTo(ordersBefore);
    }

    @Test
    void realProductsStayBuyableWhileDemoModeIsOn() throws Exception {
        Item real = newPublishedProduct("Real Brow Kit");
        Item demo = newPublishedProduct("Demo Brow Kit");
        makeItDemo(demo); // a published demo product exists, which is what "demo mode is on" means
        String session = UUID.randomUUID().toString();

        add(session, real);
        MvcResult placed = checkout(session);

        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        String orderNo = json.readTree(placed.getResponse().getContentAsString()).get("order").get("orderNo").asText();
        assertThat(jdbc.queryForObject("SELECT is_mock_data FROM customer_order WHERE order_no = ?", Boolean.class, orderNo))
                .as("a real shopper's order is a real order").isFalse();
    }

    @Test
    void realProductsAreBuyableWhenDemoModeIsOffToo() throws Exception {
        Item real = newPublishedProduct("Real Lash Glue");
        String session = UUID.randomUUID().toString();

        add(session, real);
        MvcResult placed = checkout(session);

        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
    }
}
