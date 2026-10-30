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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The stock take, category by category (owner, 2026-10-30): items grouped by subcategory, counted, none on the shelf or
 * skipped, a subcategory set to one number for every item, an item added from the shelf, and the category applied when
 * finished (only once nothing is left untouched); counted items stop being "not counted yet".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockTakeIntegrationTest {

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

    private MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult r = call(request, body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 6);
    }

    private String category(String name, String parentId) throws Exception {
        return ok(post("/api/v1/categories"), "{\"name\":\"" + name + "\""
                + (parentId != null ? ",\"parentId\":\"" + parentId + "\"" : "") + "}").get("id").asText();
    }

    private String product(String name, String categoryId) throws Exception {
        return ok(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"categoryIds\":[\"" + categoryId
                + "\"],\"published\":true,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"300\",\"isActive\":true}]}")
                .get("variants").get(0).get("id").asText();
    }

    private BigDecimal onHand(String variantId) {
        List<BigDecimal> rows = jdbc.queryForList(
                "SELECT on_hand_qty FROM stock_balance WHERE variant_id = ? AND account = 'SHOP'", BigDecimal.class,
                UUID.fromString(variantId));
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    private static List<String> variantIdsOf(JsonNode group) {
        List<String> ids = new ArrayList<>();
        group.get("lines").forEach(l -> ids.add(l.get("variantId").asText()));
        return ids;
    }

    @Test
    void aCategoryIsCountedGroupBySubcategoryAndAppliedWhenFinished() throws Exception {
        String top = category(unique("Nails"), null);
        String tips = category(unique("Tips"), top);
        String files = category(unique("Files"), top);
        String tipA = product(unique("Tip A"), tips);
        String tipB = product(unique("Tip B"), tips);
        String file = product(unique("File"), files);
        String loose = product(unique("Loose"), top);

        JsonNode take = ok(post("/api/v1/stock/takes"), null);
        String takeId = take.get("id").asText();
        assertThat(ok(post("/api/v1/stock/takes"), null).get("id").asText()).as("one open at a time").isEqualTo(takeId);
        JsonNode waiting = null;
        for (JsonNode c : take.get("categories")) if (c.get("categoryId").asText().equals(top)) waiting = c;
        assertThat(waiting).isNotNull();
        assertThat(waiting.get("status").asText()).isEqualTo("WAITING");
        assertThat(waiting.get("items").asInt()).isEqualTo(4);

        JsonNode count = ok(post("/api/v1/stock/takes/" + takeId + "/categories/" + top + "/open"), null);
        String countId = count.get("countId").asText();
        assertThat(count.get("groups")).hasSize(3);
        JsonNode tipsGroup = null;
        for (JsonNode g : count.get("groups")) if (tips.equals(g.get("subcategoryId").asText(null))) tipsGroup = g;
        assertThat(variantIdsOf(tipsGroup)).containsExactlyInAnyOrder(tipA, tipB);
        assertThat(count.get("groups").get(2).get("name").asText()).startsWith("Other ");
        assertThat(tipsGroup.get("lines").get(0).get("untracked").asBoolean()).isTrue();
        // opening it again joins the same count
        assertThat(ok(post("/api/v1/stock/takes/" + takeId + "/categories/" + top + "/open"), null)
                .get("countId").asText()).isEqualTo(countId);

        // every tip set to 300, the file counted as 7, the loose item left untouched: not finished yet
        ok(post("/api/v1/stock/takes/counts/" + countId + "/set-all"),
                "{\"subcategoryId\":\"" + tips + "\",\"wholeCategory\":false,\"qty\":300}");
        ok(put("/api/v1/stock/takes/counts/" + countId + "/lines/" + file), "{\"qty\":7}");
        MvcResult early = call(post("/api/v1/stock/takes/counts/" + countId + "/finish"), null);
        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        assertThat(early.getResponse().getContentAsString()).contains("1 item is still to count");

        // the loose item is skipped (left as it was); an item found on the shelf is added to Files with 12
        ok(post("/api/v1/stock/takes/counts/" + countId + "/lines/" + loose + "/skip"), null);
        JsonNode added = ok(post("/api/v1/stock/takes/counts/" + countId + "/items"),
                "{\"name\":\"" + unique("Glass File") + "\",\"price\":\"450\",\"subcategoryId\":\"" + files + "\",\"qty\":12}");
        assertThat(added.get("total").asInt()).isEqualTo(5);

        JsonNode done = ok(post("/api/v1/stock/takes/counts/" + countId + "/finish"), null);
        assertThat(done.get("status").asText()).isEqualTo("APPLIED");
        assertThat(onHand(tipA)).isEqualByComparingTo("300");
        assertThat(onHand(tipB)).isEqualByComparingTo("300");
        assertThat(onHand(file)).isEqualByComparingTo("7");
        assertThat(onHand(loose)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT stock_basis FROM product_variant WHERE id = ?", String.class,
                UUID.fromString(tipA))).isEqualTo("COUNTED");
        assertThat(jdbc.queryForObject("SELECT stock_basis FROM product_variant WHERE id = ?", String.class,
                UUID.fromString(loose))).isEqualTo("NONE");
        // the added item is live in the shop with its 12
        String addedId = null;
        for (JsonNode g : done.get("groups")) {
            for (JsonNode l : g.get("lines")) if (l.get("name").asText().startsWith("Glass File")) addedId = l.get("variantId").asText();
        }
        assertThat(addedId).isNotNull();
        assertThat(onHand(addedId)).isEqualByComparingTo("12");
        assertThat(jdbc.queryForObject("SELECT p.is_published FROM product p JOIN product_variant v ON v.product_id = p.id"
                + " WHERE v.id = ?", Boolean.class, UUID.fromString(addedId))).isTrue();

        JsonNode after = ok(get("/api/v1/stock/takes/" + takeId), null);
        for (JsonNode c : after.get("categories")) {
            if (c.get("categoryId").asText().equals(top)) {
                assertThat(c.get("status").asText()).isEqualTo("DONE");
                assertThat(c.get("countedBy").asText()).isNotBlank();
            }
        }
        ok(post("/api/v1/stock/takes/" + takeId + "/close"), null);
        assertThat(ok(get("/api/v1/stock/takes/current"), null).get("take").isNull()).isTrue();
    }
}
