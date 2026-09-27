package com.nbh.backend.catalog;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The merchandising feature end to end, through the real security chain and database: flash deals,
 * the site-wide campaign, the "product deal wins over campaign" rule, and — the part that actually
 * matters financially — that the discounted price is what gets locked into the cart, not just
 * shown. See NairobiBeautyHub_Merchandising_Promo_Plan.md for the design this implements.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MerchandisingIntegrationTest {

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

    /** Campaigns are real rows with no cleanup endpoint — deactivate whatever this test created so it can't leak into another test run. */
    private final java.util.List<String> campaignIdsToDeactivate = new java.util.ArrayList<>();

    @AfterEach
    void deactivateCampaignsCreatedByThisTest() throws Exception {
        for (String id : campaignIdsToDeactivate) {
            call(put("/api/v1/admin/promo-campaigns/" + id + "/active"), "{\"isActive\":false}");
        }
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

    private int status(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request, body).getResponse().getStatus();
    }

    private JsonNode newProduct(String name, String price) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"" + price + "\",\"isActive\":true}]}";
        JsonNode product = ok(post("/api/v1/products/draft"), body);
        ok(post("/api/v1/products/" + product.get("id").asText() + "/publish"), null);
        // Give it real stock so it's addable to a cart and shows up as inStock on the catalogue.
        String variantId = product.get("variants").get(0).get("id").asText();
        // Phase 2 fix (2026-09-27): a unit cost is now required for a stock gain with no
        // existing average to fall back on (VariantStockService no longer silently posts at
        // zero cost).
        ok(put("/api/v1/products/" + product.get("id").asText() + "/variants/" + variantId + "/stock"),
                "{\"quantity\":10,\"unitCost\":100}");
        return product;
    }

    /** {@code q} searches the product NAME, not the slug — pass the name here, not the slug. One card per variant (Phase 3e). */
    private JsonNode catalogueEntryByName(String name) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/catalog/variants")
                .param("q", name).param("size", "5")).andReturn();
        JsonNode content = json.readTree(result.getResponse().getContentAsString()).get("content");
        for (JsonNode p : content) {
            if (p.get("productName").asText().equals(name)) return p;
        }
        throw new AssertionError("Product named \"" + name + "\" not found in the public catalogue: "
                + result.getResponse().getContentAsString());
    }

    @Test
    void settingAFlashDealShowsTheDiscountedPriceOnTheCatalogueAndClearingItRevertsIt() throws Exception {
        JsonNode product = newProduct(unique("Flash Deal Product"), "1000");
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        String name = product.get("name").asText();
        Instant endsAt = Instant.now().plus(2, ChronoUnit.HOURS);

        JsonNode set = ok(put("/api/v1/products/" + productId + "/variants/" + variantId + "/deal"),
                "{\"dealPrice\":\"650\",\"dealEndsAt\":\"" + endsAt + "\"}");
        assertThat(set.get("dealPrice").decimalValue()).isEqualByComparingTo("650");

        JsonNode entry = catalogueEntryByName(name);
        assertThat(entry.get("price").decimalValue()).isEqualByComparingTo("650");
        assertThat(entry.get("originalPrice").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(entry.get("onDeal").asBoolean()).isTrue();
        assertThat(entry.get("dealEndsAt").asText()).isNotBlank();

        ok(delete("/api/v1/products/" + productId + "/variants/" + variantId + "/deal"), null);
        JsonNode cleared = catalogueEntryByName(name);
        assertThat(cleared.get("price").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(cleared.get("onDeal").asBoolean()).isFalse();
    }

    @Test
    void aDealPriceMustBeLessThanTheSellingPriceAndTheDeadlineMustBeInTheFuture() throws Exception {
        JsonNode product = newProduct(unique("Bad Deal Product"), "500");
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        Instant future = Instant.now().plusSeconds(3600);
        Instant past = Instant.now().minusSeconds(3600);

        assertThat(status(put("/api/v1/products/" + productId + "/variants/" + variantId + "/deal"),
                "{\"dealPrice\":\"500\",\"dealEndsAt\":\"" + future + "\"}")).isEqualTo(400); // not less than selling price
        assertThat(status(put("/api/v1/products/" + productId + "/variants/" + variantId + "/deal"),
                "{\"dealPrice\":\"400\",\"dealEndsAt\":\"" + past + "\"}")).isEqualTo(400); // deadline in the past
    }

    @Test
    void aSiteWideCampaignDiscountsAProductWithNoDealOfItsOwn() throws Exception {
        JsonNode product = newProduct(unique("Campaign Product"), "1000");
        String name = product.get("name").asText();

        JsonNode campaign = ok(post("/api/v1/admin/promo-campaigns"),
                "{\"name\":\"" + unique("Test Campaign") + "\",\"percentOff\":20,\"startsAt\":\""
                        + Instant.now().minusSeconds(60) + "\",\"endsAt\":\"" + Instant.now().plusSeconds(3600) + "\",\"isActive\":true}");
        campaignIdsToDeactivate.add(campaign.get("id").asText());

        JsonNode entry = catalogueEntryByName(name);
        assertThat(entry.get("price").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(entry.get("onDeal").asBoolean()).isTrue();

        JsonNode publicCampaign = json.readTree(
                mvc.perform(get("/api/v1/catalog/promo-campaign")).andReturn().getResponse().getContentAsString());
        assertThat(publicCampaign.get("percentOff").decimalValue()).isEqualByComparingTo("20");
        assertThat(publicCampaign.get("isCurrentlyActive").asBoolean()).isTrue();
    }

    @Test
    void aProductsOwnDealWinsOverAnActiveCampaignEvenThoughBothWouldApply() throws Exception {
        JsonNode product = newProduct(unique("Own Deal Wins Product"), "1000");
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        String name = product.get("name").asText();

        JsonNode campaign = ok(post("/api/v1/admin/promo-campaigns"),
                "{\"name\":\"" + unique("Overridden Campaign") + "\",\"percentOff\":50,\"startsAt\":\""
                        + Instant.now().minusSeconds(60) + "\",\"endsAt\":\"" + Instant.now().plusSeconds(3600) + "\",\"isActive\":true}");
        campaignIdsToDeactivate.add(campaign.get("id").asText());
        // A 50% campaign would be 500; this product's own deal (700) is less generous but must still win.
        ok(put("/api/v1/products/" + productId + "/variants/" + variantId + "/deal"),
                "{\"dealPrice\":\"700\",\"dealEndsAt\":\"" + Instant.now().plusSeconds(1800) + "\"}");

        JsonNode entry = catalogueEntryByName(name);
        assertThat(entry.get("price").decimalValue()).isEqualByComparingTo("700");
    }

    @Test
    void addingADiscountedProductToCartLocksInTheDiscountedPriceNotTheOriginal() throws Exception {
        JsonNode product = newProduct(unique("Cart Price Lock Product"), "1000");
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        ok(put("/api/v1/products/" + productId + "/variants/" + variantId + "/deal"),
                "{\"dealPrice\":\"650\",\"dealEndsAt\":\"" + Instant.now().plusSeconds(3600) + "\"}");

        String sessionId = UUID.randomUUID().toString();
        MvcResult addResult = mvc.perform(post("/api/v1/cart/items")
                        .header("X-Cart-Session", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"qty\":2}"))
                .andReturn();
        assertThat(addResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode cart = json.readTree(addResult.getResponse().getContentAsString());
        JsonNode item = cart.get("items").get(0);

        assertThat(item.get("unitPrice").decimalValue()).isEqualByComparingTo("650");
        assertThat(item.get("lineTotal").decimalValue()).isEqualByComparingTo("1300");
    }

    @Test
    void popularAndNewArrivalFlagsRoundTripThroughSaveAndTheCatalogueFilter() throws Exception {
        JsonNode created = newProduct(unique("Flags Product"), "500");
        String productId = created.get("id").asText();
        assertThat(created.get("isPopular").asBoolean()).isFalse();
        assertThat(created.get("isNewArrival").asBoolean()).isFalse();

        String variantId = created.get("variants").get(0).get("id").asText();
        JsonNode updated = ok(put("/api/v1/products/" + productId + "/full"), "{"
                + "\"expectedUpdatedAt\":\"" + created.get("updatedAt").asText() + "\","
                + "\"name\":\"" + created.get("name").asText() + "\",\"categoryIds\":[],"
                + "\"published\":true,\"isPopular\":true,\"isNewArrival\":true,"
                + "\"variants\":[{\"id\":\"" + variantId + "\",\"variantName\":\"Default\","
                + "\"sellingPrice\":\"500\",\"isActive\":true}]}");
        assertThat(updated.get("isPopular").asBoolean()).isTrue();
        assertThat(updated.get("isNewArrival").asBoolean()).isTrue();

        JsonNode entry = catalogueEntryByName(created.get("name").asText());
        assertThat(entry.get("isPopular").asBoolean()).isTrue();
        assertThat(entry.get("isNewArrival").asBoolean()).isTrue();

        assertThat(ok(get("/api/v1/products").param("q", created.get("name").asText()).param("popular", "true"), null)
                .get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void twoOverlappingActiveCampaignsAreRefused() throws Exception {
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(3600);
        JsonNode first = ok(post("/api/v1/admin/promo-campaigns"),
                "{\"name\":\"" + unique("First Campaign") + "\",\"percentOff\":10,\"startsAt\":\"" + start
                        + "\",\"endsAt\":\"" + end + "\",\"isActive\":true}");
        campaignIdsToDeactivate.add(first.get("id").asText());

        assertThat(status(post("/api/v1/admin/promo-campaigns"),
                "{\"name\":\"" + unique("Overlapping Campaign") + "\",\"percentOff\":15,\"startsAt\":\"" + start
                        + "\",\"endsAt\":\"" + end + "\",\"isActive\":true}")).isEqualTo(409);
    }
}
