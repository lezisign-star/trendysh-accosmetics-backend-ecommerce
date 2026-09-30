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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The admin product list's bulk-action bar ({@code POST /products/bulk}, Phase 3, 2026-09-24) —
 * one transaction, one action, across several products at once. Unique names per run so it can
 * share the database with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductBulkActionsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;

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

    private String newCategory(String name, String parentId) throws Exception {
        String body = "{\"name\":\"" + name + "\"" + (parentId != null ? ",\"parentId\":\"" + parentId + "\"" : "") + "}";
        return ok(post("/api/v1/categories"), body).get("id").asText();
    }

    private JsonNode newDraft(String name, String categoryId, String price) throws Exception {
        String body = "{\"name\":\"" + name + "\","
                + "\"categoryIds\":" + (categoryId != null ? "[\"" + categoryId + "\"]" : "[]") + ","
                + "\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Only\"," + (price != null ? "\"sellingPrice\":\"" + price + "\"," : "")
                + "\"isActive\":true}]}";
        return ok(post("/api/v1/products/draft"), body);
    }

    @Test
    void publishAndUnpublishActOnEveryProductAtOnce() throws Exception {
        JsonNode a = newDraft(unique("Bulk Publish A"), null, "100");
        JsonNode b = newDraft(unique("Bulk Publish B"), null, "200");
        String ids = "[\"" + a.get("id").asText() + "\",\"" + b.get("id").asText() + "\"]";

        JsonNode publishResult = ok(post("/api/v1/products/bulk"),
                "{\"action\":\"PUBLISH\",\"productIds\":" + ids + "}");
        assertThat(publishResult.get("affectedProducts").asInt()).isEqualTo(2);
        assertThat(ok(get("/api/v1/products/" + a.get("id").asText()), null).get("isPublished").asBoolean()).isTrue();
        assertThat(ok(get("/api/v1/products/" + b.get("id").asText()), null).get("isPublished").asBoolean()).isTrue();

        ok(post("/api/v1/products/bulk"), "{\"action\":\"UNPUBLISH\",\"productIds\":" + ids + "}");
        assertThat(ok(get("/api/v1/products/" + a.get("id").asText()), null).get("isPublished").asBoolean()).isFalse();
        assertThat(ok(get("/api/v1/products/" + b.get("id").asText()), null).get("isPublished").asBoolean()).isFalse();
    }

    @Test
    void deleteThenRestoreRoundTrips() throws Exception {
        JsonNode a = newDraft(unique("Bulk Delete A"), null, "100");
        String ids = "[\"" + a.get("id").asText() + "\"]";

        JsonNode deleteResult = ok(post("/api/v1/products/bulk"), "{\"action\":\"DELETE\",\"productIds\":" + ids + "}");
        assertThat(deleteResult.get("affectedProducts").asInt()).isEqualTo(1);
        assertThat(ok(get("/api/v1/products/" + a.get("id").asText()), null).get("deleted").asBoolean()).isTrue();

        ok(post("/api/v1/products/bulk"), "{\"action\":\"RESTORE\",\"productIds\":" + ids + "}");
        assertThat(ok(get("/api/v1/products/" + a.get("id").asText()), null).get("deleted").asBoolean()).isFalse();
    }

    @Test
    void addCategoriesMergesWhileReplaceCategoriesOverwrites() throws Exception {
        String nails = newCategory(unique("Nails"), null);
        String lashes = newCategory(unique("Lashes"), null);
        String spa = newCategory(unique("Spa"), null);

        JsonNode product = newDraft(unique("Category Bulk Product"), nails, "100");
        String ids = "[\"" + product.get("id").asText() + "\"]";

        ok(post("/api/v1/products/bulk"), "{\"action\":\"ADD_CATEGORIES\",\"productIds\":" + ids
                + ",\"categoryIds\":[\"" + lashes + "\"]}");
        JsonNode afterAdd = ok(get("/api/v1/products/" + product.get("id").asText()), null);
        assertThat(afterAdd.get("categories")).hasSize(2);

        ok(post("/api/v1/products/bulk"), "{\"action\":\"REPLACE_CATEGORIES\",\"productIds\":" + ids
                + ",\"categoryIds\":[\"" + spa + "\"]}");
        JsonNode afterReplace = ok(get("/api/v1/products/" + product.get("id").asText()), null);
        assertThat(afterReplace.get("categories")).hasSize(1);
        assertThat(afterReplace.get("categories").get(0).get("id").asText()).isEqualTo(spa);
    }

    @Test
    void adjustPriceRoundsHalfUpAndClearsASaleThatsNoLongerBelowTheNewPrice() throws Exception {
        JsonNode product = newDraft(unique("Adjust Price Product"), null, "100");
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();

        // A sale price that's currently valid (90 < 100)...
        ok(put("/api/v1/products/" + productId + "/variants/" + variantId + "/deal"),
                "{\"dealPrice\":\"90\",\"dealEndsAt\":\"" + Instant.now().plusSeconds(3600) + "\"}");

        // ...-30% drops the regular price to 70, which is now BELOW the 90 deal price: the deal
        // must be cleared, not left silently wrong (a deal price above the real selling price).
        JsonNode result = ok(post("/api/v1/products/bulk"), "{\"action\":\"ADJUST_PRICE\","
                + "\"productIds\":[\"" + productId + "\"],\"percent\":-30}");
        assertThat(result.get("affectedVariants").asInt()).isEqualTo(1);
        assertThat(result.get("warnings")).anyMatch(w -> w.asText().contains("sale"));

        JsonNode after = ok(get("/api/v1/products/" + productId), null);
        JsonNode variant = after.get("variants").get(0);
        assertThat(variant.get("sellingPrice").asDouble()).isEqualTo(70.0);
        assertThat(variant.get("dealPrice").isNull()).isTrue();
    }

    @Test
    void adjustPriceClearsAWholesaleTierThatsNoLongerBelowTheNewPriceButKeepsOneThatStillIs() throws Exception {
        JsonNode stale = newDraft(unique("Adjust Wholesale Stale"), null, "100");
        String staleId = stale.get("id").asText();
        ok(put("/api/v1/products/" + staleId + "/variants/" + stale.get("variants").get(0).get("id").asText()
                + "/wholesale"), "{\"minQty\":10,\"wholesalePrice\":\"80\"}");
        JsonNode kept = newDraft(unique("Adjust Wholesale Kept"), null, "100");
        String keptId = kept.get("id").asText();
        ok(put("/api/v1/products/" + keptId + "/variants/" + kept.get("variants").get(0).get("id").asText()
                + "/wholesale"), "{\"minQty\":10,\"wholesalePrice\":\"50\"}");

        // -30% takes both to 70: the 80 tier would now cost MORE than the regular price, the 50 one wouldn't.
        JsonNode result = ok(post("/api/v1/products/bulk"), "{\"action\":\"ADJUST_PRICE\","
                + "\"productIds\":[\"" + staleId + "\",\"" + keptId + "\"],\"percent\":-30}");
        assertThat(result.get("warnings")).anyMatch(w -> w.asText().contains("wholesale"));

        JsonNode staleVariant = ok(get("/api/v1/products/" + staleId), null).get("variants").get(0);
        assertThat(staleVariant.get("wholesalePrice").isNull()).isTrue();
        assertThat(staleVariant.get("wholesaleMinQty").isNull()).isTrue();
        JsonNode keptVariant = ok(get("/api/v1/products/" + keptId), null).get("variants").get(0);
        assertThat(keptVariant.get("wholesalePrice").asDouble()).isEqualTo(50.0);
        assertThat(keptVariant.get("wholesaleMinQty").asInt()).isEqualTo(10);
    }

    @Test
    void adjustPriceSkipsAPricelessVariantWithAWarningInsteadOfFailing() throws Exception {
        JsonNode draft = ok(post("/api/v1/products/draft"), "{\"name\":\"" + unique("Priceless Bulk Product")
                + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"No Price Yet\",\"isActive\":true}]}");
        String productId = draft.get("id").asText();

        JsonNode result = ok(post("/api/v1/products/bulk"), "{\"action\":\"ADJUST_PRICE\","
                + "\"productIds\":[\"" + productId + "\"],\"percent\":20}");
        assertThat(result.get("affectedVariants").asInt()).isZero();
        assertThat(result.get("warnings")).anyMatch(w -> w.asText().contains("no price"));
    }
}
