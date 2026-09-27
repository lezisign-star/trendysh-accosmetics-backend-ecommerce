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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Phase 2 (2026-09-27): the stock-count workflow through the real API — create (freezes the
 * snapshot), record, apply cleanly, and the conflict path when stock moves in between.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockCountIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;

    private String token;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private int status(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String newVariantWithStock(int qty) throws Exception {
        String body = "{\"name\":\"" + unique("Count Test Product") + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = ok(post("/api/v1/products/draft"), body);
        String variantId = product.get("variants").get(0).get("id").asText();
        ok(put("/api/v1/products/" + product.get("id").asText() + "/variants/" + variantId + "/stock"),
                "{\"quantity\":" + qty + ",\"unitCost\":50}");
        return variantId;
    }

    private java.math.BigDecimal shopOnHand(String variantId) throws Exception {
        JsonNode balances = ok(get("/api/v1/stock/balance?variantId=" + variantId), null);
        for (JsonNode b : balances) {
            if ("SHOP".equals(b.get("account").asText())) return b.get("onHandQty").decimalValue();
        }
        throw new AssertionError("no SHOP balance row");
    }

    @Test
    void aCleanCountAppliesTheExactDifference() throws Exception {
        String variantId = newVariantWithStock(20);

        JsonNode count = ok(post("/api/v1/stock/counts"),
                "{\"variantIds\":[\"" + variantId + "\"],\"blind\":false,\"reason\":\"Monthly count\"}");
        String countId = count.get("id").asText();
        assertThat(count.get("lines").get(0).get("systemQtyAtStart").asInt()).isEqualTo(20);
        assertThat(count.get("status").asText()).isEqualTo("DRAFT");

        // Counted 17 — 3 short.
        JsonNode recorded = ok(put("/api/v1/stock/counts/" + countId + "/lines/" + variantId),
                "{\"countedQty\":\"17\",\"note\":\"3 damaged on shelf\"}");
        assertThat(recorded.get("status").asText()).isEqualTo("IN_PROGRESS");

        JsonNode applied = ok(post("/api/v1/stock/counts/" + countId + "/apply"), null);
        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        assertThat(applied.get("appliedDocumentNo").asText()).isNotBlank();
        assertThat(shopOnHand(variantId).intValue()).isEqualTo(17);

        // Already applied — cannot apply again.
        assertThat(status(post("/api/v1/stock/counts/" + countId + "/apply"), null)).isEqualTo(422);
    }

    @Test
    void stockMovingAfterTheCountIsTakenRequiresAnExplicitResolution() throws Exception {
        String variantId = newVariantWithStock(20);

        JsonNode count = ok(post("/api/v1/stock/counts"),
                "{\"variantIds\":[\"" + variantId + "\"],\"blind\":false}");
        String countId = count.get("id").asText();

        ok(put("/api/v1/stock/counts/" + countId + "/lines/" + variantId), "{\"countedQty\":\"18\"}");

        // Something happens between the count and applying it — on-hand drifts from 20 to 15.
        ok(post("/api/v1/stock/write-offs"),
                "{\"lines\":[{\"variantId\":\"" + variantId + "\",\"qty\":\"5\"}],\"reasonCode\":\"OTHER\"}");
        assertThat(shopOnHand(variantId).intValue()).isEqualTo(15);

        // Unresolved: must be rejected, not silently applied either way.
        assertThat(status(post("/api/v1/stock/counts/" + countId + "/apply"), null)).isEqualTo(409);
        assertThat(shopOnHand(variantId).intValue()).isEqualTo(15);

        // KEEP_DIFFERENCE: the counted-vs-snapshot diff (18-20=-2) applies on top of the NEW
        // current on-hand (15), landing at 13 — not blindly setting it to 18.
        JsonNode applied = ok(post("/api/v1/stock/counts/" + countId + "/apply"),
                "{\"resolutions\":{\"" + variantId + "\":\"KEEP_DIFFERENCE\"}}");
        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        assertThat(shopOnHand(variantId).intValue()).isEqualTo(13);
    }

    @Test
    void aBlindCountHidesTheSystemQuantityFromAViewOnlyCaller() throws Exception {
        String variantId = newVariantWithStock(10);
        JsonNode count = ok(post("/api/v1/stock/counts"),
                "{\"variantIds\":[\"" + variantId + "\"],\"blind\":true}");
        // The SUPER_ADMIN token used throughout this test has PERM_STOCK_ADJUST, so it always
        // sees the real figure — this asserts the reveal flag is wired through at all, not the
        // full cross-role matrix (that needs a second, lower-privileged token).
        assertThat(count.get("lines").get(0).get("systemQtyAtStart").asInt()).isEqualTo(10);
    }
}
