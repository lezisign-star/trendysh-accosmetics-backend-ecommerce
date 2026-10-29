package com.nbh.backend.catalog;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Combining products into one (owner, 2026-10-29): products made once per colour join a main product, whose
 * configuration then holds their settings; photos, reviews and counts follow; a split puts every row back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductCombineIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void signInAsSuperAdmin() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = call(request, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private JsonNode product(String name, String price, String cat) throws Exception {
        return ok(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"description\":\"about " + name + "\","
                + "\"categoryIds\":[\"" + cat + "\"],\"published\":true,"
                + "\"variants\":[{\"variantName\":\"" + name + "\",\"sellingPrice\":\"" + price + "\",\"isActive\":true}]}");
    }

    private UUID loosePhoto(String productId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO product_image (id, product_id, url, sort_order, is_primary, source, created_at, updated_at)"
                + " VALUES (?, ?, ?, 0, true, 'UPLOAD', now(), now())", id, UUID.fromString(productId),
                "https://res.cloudinary.com/demo/" + id + ".jpg");
        return id;
    }

    private UUID order() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customer_order (id, order_no, guest_email, guest_phone, fulfilment_type, status, subtotal,"
                + " vat_total, grand_total, delivery_address, customer_name) VALUES (?, ?, 'r@example.com', '254711222333',"
                + " 'PICKUP', 'PAID', 1000, 0, 1000, 'Kilimani', 'Reviewer')", id, "CMB-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID review(String productId, UUID orderId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO product_review (id, product_id, order_id, rating, comment, reviewer_name, reviewer_phone,"
                + " status, created_at, updated_at) VALUES (?, ?, ?, 5, 'lovely', 'Amina', '254711222333', 'APPROVED', now(), now())",
                id, UUID.fromString(productId), orderId);
        return id;
    }

    private UUID productOf(String table, UUID id) {
        return jdbc.queryForObject("SELECT product_id FROM " + table + " WHERE id = ?", UUID.class, id);
    }

    private String rows(String... rows) {
        return "[" + String.join(",", rows) + "]";
    }

    private static String row(String variantId, String part, String color) {
        return "{\"variantId\":\"" + variantId + "\",\"namePart\":\"" + part + "\",\"color\":\"" + color + "\"}";
    }

    @Test
    void combiningMovesEverythingUnderTheMainAndSplittingBackPutsItAllBack() throws Exception {
        String cat = ok(post("/api/v1/categories"), "{\"name\":\"" + unique("Nails") + "\"}").get("id").asText();
        String base = unique("Gel Polish");
        JsonNode pink = product(base + " Pink", "500", cat);
        JsonNode red = product(base + " Red", "550", cat);
        JsonNode nude = product(base + " Nude", "500", cat);
        String pinkId = pink.get("id").asText(), redId = red.get("id").asText(), nudeId = nude.get("id").asText();
        String pinkV = pink.get("variants").get(0).get("id").asText();
        String redV = red.get("variants").get(0).get("id").asText();
        String nudeV = nude.get("variants").get(0).get("id").asText();
        UUID redPhoto = loosePhoto(redId);
        UUID pinkPhoto = loosePhoto(pinkId);

        UUID first = order();
        UUID redReview = review(redId, first);
        UUID both = order();
        review(pinkId, both);
        UUID nudeReview = review(nudeId, both); // the same order already reviewed the main: it stays where it was
        UUID sale = UUID.randomUUID();
        jdbc.update("INSERT INTO product_sale_event (id, product_id, variant_id, order_id, qty, occurred_at)"
                + " VALUES (?, ?, ?, ?, 2, now())", sale, UUID.fromString(redId), UUID.fromString(redV), first);

        JsonNode preview = ok(post("/api/v1/products/combine/preview"),
                "{\"mainProductId\":\"" + pinkId + "\",\"productIds\":[\"" + redId + "\",\"" + nudeId + "\"]}");
        assertThat(preview.get("blocks")).isEmpty();
        assertThat(preview.get("products")).hasSize(3);
        assertThat(preview.get("products").get(0).get("main").asBoolean()).isTrue();
        assertThat(preview.get("products").get(1).get("variants").get(0).get("price").asDouble()).isEqualTo(550.0);

        JsonNode result = ok(post("/api/v1/products/combine"), "{\"mainProductId\":\"" + pinkId + "\","
                + "\"productIds\":[\"" + redId + "\",\"" + nudeId + "\"],\"name\":\"" + base + "\",\"rows\":"
                + rows(row(pinkV, "Pink", "Pink"), row(redV, "Red", "Red"), row(nudeV, "Nude", "Nude")) + "}");
        assertThat(result.get("variants").asInt()).isEqualTo(3);
        assertThat(result.get("reviewsMoved").asInt()).isEqualTo(1);
        assertThat(result.get("reviewsKeptAside").asInt()).isEqualTo(1);

        // One product: the main's name, every variant named from it, prices untouched and allowed to differ.
        JsonNode main = ok(get("/api/v1/products/" + pinkId), null);
        assertThat(main.get("name").asText()).isEqualTo(base);
        assertThat(main.get("variants")).hasSize(3);
        assertThat(main.get("variants").findValuesAsText("variantName"))
                .containsExactly(base + " – Pink", base + " – Red", base + " – Nude");
        assertThat(main.get("variants").get(1).get("sellingPrice").asDouble()).isEqualTo(550.0);
        assertThat(jdbc.queryForObject("SELECT price_varies FROM product WHERE id = ?", Boolean.class,
                UUID.fromString(pinkId))).isTrue();
        assertThat(jdbc.queryForObject("SELECT range_reviewed_at IS NOT NULL FROM product WHERE id = ?", Boolean.class,
                UUID.fromString(pinkId))).isTrue();
        assertThat(jdbc.queryForList("SELECT value FROM product_option_value WHERE product_id = ? AND kind = 'COLOR'"
                + " ORDER BY position", String.class, UUID.fromString(pinkId))).containsExactly("Pink", "Red", "Nude");
        assertThat(jdbc.queryForObject("SELECT deleted AND NOT is_published FROM product WHERE id = ?", Boolean.class,
                UUID.fromString(redId))).isTrue();

        // Photos stay with their colour, reviews and counts follow.
        assertThat(productOf("product_image", redPhoto)).isEqualTo(UUID.fromString(pinkId));
        assertThat(jdbc.queryForObject("SELECT variant_id FROM product_image WHERE id = ?", UUID.class, redPhoto))
                .isEqualTo(UUID.fromString(redV));
        assertThat(jdbc.queryForObject("SELECT variant_id FROM product_image WHERE id = ?", UUID.class, pinkPhoto))
                .isEqualTo(UUID.fromString(pinkV));
        assertThat(productOf("product_review", redReview)).isEqualTo(UUID.fromString(pinkId));
        assertThat(productOf("product_review", nudeReview)).isEqualTo(UUID.fromString(nudeId));
        assertThat(productOf("product_sale_event", sale)).isEqualTo(UUID.fromString(pinkId));

        JsonNode combines = ok(get("/api/v1/products/" + pinkId + "/combines"), null);
        assertThat(combines).hasSize(1);
        assertThat(combines.get(0).get("canSplit").asBoolean()).isTrue();
        assertThat(json.convertValue(combines.get(0).get("joinedNames"), List.class))
                .containsExactly(base + " Red", base + " Nude");

        ok(post("/api/v1/products/combines/" + combines.get(0).get("id").asText() + "/split"), null);

        JsonNode back = ok(get("/api/v1/products/" + redId), null);
        assertThat(back.get("name").asText()).isEqualTo(base + " Red");
        assertThat(back.get("isPublished").asBoolean()).isTrue();
        assertThat(back.get("variants")).hasSize(1);
        assertThat(back.get("variants").get(0).get("variantName").asText()).isEqualTo(base + " Red");
        JsonNode mainBack = ok(get("/api/v1/products/" + pinkId), null);
        assertThat(mainBack.get("name").asText()).isEqualTo(base + " Pink");
        assertThat(mainBack.get("variants")).hasSize(1);
        assertThat(mainBack.get("variants").get(0).get("variantName").asText()).isEqualTo(base + " Pink");
        assertThat(jdbc.queryForObject("SELECT range_reviewed_at IS NULL AND NOT price_varies FROM product WHERE id = ?",
                Boolean.class, UUID.fromString(pinkId))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_option_value WHERE product_id = ?", Integer.class,
                UUID.fromString(pinkId))).isZero();
        assertThat(productOf("product_image", redPhoto)).isEqualTo(UUID.fromString(redId));
        assertThat(jdbc.queryForObject("SELECT variant_id IS NULL FROM product_image WHERE id = ?", Boolean.class, redPhoto))
                .isTrue();
        assertThat(productOf("product_review", redReview)).isEqualTo(UUID.fromString(redId));
        assertThat(productOf("product_sale_event", sale)).isEqualTo(UUID.fromString(redId));
        assertThat(ok(get("/api/v1/products/" + pinkId + "/combines"), null).get(0).get("splitAt").isNull()).isFalse();
        assertThat(call(post("/api/v1/products/combines/" + combines.get(0).get("id").asText() + "/split"), null)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void twoWithTheSameColourOrAProductAlreadyCombinedIntoAreRefused() throws Exception {
        String cat = ok(post("/api/v1/categories"), "{\"name\":\"" + unique("Lashes") + "\"}").get("id").asText();
        String base = unique("Lash Glue");
        JsonNode a = product(base + " Black", "300", cat);
        JsonNode b = product(base + " Clear", "300", cat);
        JsonNode c = product(base + " White", "300", cat);
        String aId = a.get("id").asText(), bId = b.get("id").asText(), cId = c.get("id").asText();
        String aV = a.get("variants").get(0).get("id").asText();
        String bV = b.get("variants").get(0).get("id").asText();
        String cV = c.get("variants").get(0).get("id").asText();

        MvcResult same = call(post("/api/v1/products/combine"), "{\"mainProductId\":\"" + aId + "\",\"productIds\":[\""
                + bId + "\"],\"name\":\"" + base + "\",\"rows\":" + rows(row(aV, "Black", "Black"), row(bV, "Clear", "black")) + "}");
        assertThat(same.getResponse().getStatus()).isEqualTo(422);
        assertThat(same.getResponse().getContentAsString()).contains("Two would both be");

        MvcResult missing = call(post("/api/v1/products/combine"), "{\"mainProductId\":\"" + aId + "\",\"productIds\":[\""
                + bId + "\"],\"name\":\"" + base + "\",\"rows\":" + rows(row(aV, "Black", "Black")) + "}");
        assertThat(missing.getResponse().getStatus()).isEqualTo(422);

        ok(post("/api/v1/products/combine"), "{\"mainProductId\":\"" + aId + "\",\"productIds\":[\"" + bId + "\"],"
                + "\"name\":\"" + base + "\",\"rows\":" + rows(row(aV, "Black", "Black"), row(bV, "Clear", "Clear")) + "}");
        // Equal prices: one shared price, nothing allowed to differ.
        assertThat(jdbc.queryForObject("SELECT NOT price_varies AND shared_price = 300 FROM product WHERE id = ?",
                Boolean.class, UUID.fromString(aId))).isTrue();

        JsonNode preview = ok(post("/api/v1/products/combine/preview"),
                "{\"mainProductId\":\"" + cId + "\",\"productIds\":[\"" + aId + "\"]}");
        assertThat(preview.get("blocks").get(0).asText()).contains("Choose it as the main");

        // Combining more into the same main works; only the latest combine can be split back.
        JsonNode more = ok(post("/api/v1/products/combine/preview"),
                "{\"mainProductId\":\"" + aId + "\",\"productIds\":[\"" + cId + "\"]}");
        assertThat(more.get("blocks")).isEmpty();
        assertThat(more.get("products").get(0).get("variants")).hasSize(2);
        ok(post("/api/v1/products/combine"), "{\"mainProductId\":\"" + aId + "\",\"productIds\":[\"" + cId + "\"],"
                + "\"name\":\"" + base + "\",\"rows\":" + rows(row(aV, "Black", "Black"), row(bV, "Clear", "Clear"),
                row(cV, "White", "White")) + "}");
        JsonNode list = ok(get("/api/v1/products/" + aId + "/combines"), null);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("canSplit").asBoolean()).isTrue();
        assertThat(list.get(1).get("canSplit").asBoolean()).isFalse();
        assertThat(call(post("/api/v1/products/combines/" + list.get(1).get("id").asText() + "/split"), null)
                .getResponse().getStatus()).isEqualTo(409);
    }
}
