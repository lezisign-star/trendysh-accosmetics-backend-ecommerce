package com.nbh.backend.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Found in the 2026-10-06 go-live QA: a stock count of 2.5 was saved for a lipstick, a cart took 1.5 of an item, and a
 * count of a trillion reached the database and came back as "A conflicting record already exists". A typed-in quantity
 * has to be a count the shop could really hold: whole units for anything sold by the piece, and nothing absurd.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuantityEnforcementIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ProductVariantRepository variantRepository;

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
        MvcResult result = call(request, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private MvcResult refused(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = call(request, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(400);
        return result;
    }

    private JsonNode newProduct() throws Exception {
        String body = "{\"name\":\"Quantity Test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"isActive\":true}]}";
        return ok(post("/api/v1/products/draft"), body);
    }

    private static String stockUrl(JsonNode product) {
        return "/api/v1/products/" + product.get("id").asText() + "/variants/"
                + product.get("variants").get(0).get("id").asText() + "/stock";
    }

    private static String variantId(JsonNode product) {
        return product.get("variants").get(0).get("id").asText();
    }

    private int onHand(JsonNode product) throws Exception {
        JsonNode rows = ok(get("/api/v1/stock/inventory").param("productId", product.get("id").asText()), null).get("content");
        return rows.size() == 0 ? 0 : rows.get(0).get("onHandQty").asInt();
    }

    @Test
    void aStockCountMustBeAWholeNumberOfPiecesAndAnythingAbsurdIsATypoNotAConflict() throws Exception {
        JsonNode product = newProduct();
        String url = stockUrl(product);
        ok(put(url), "{\"quantity\":10,\"allowUnknownCost\":true}");

        refused(put(url), "{\"quantity\":2.5,\"allowUnknownCost\":true}");
        MvcResult huge = refused(put(url), "{\"quantity\":1000000000000,\"allowUnknownCost\":true}");
        assertThat(huge.getResponse().getContentAsString()).contains("too large").doesNotContain("conflicting");
        refused(put(url), "{\"quantity\":1000001,\"allowUnknownCost\":true}");

        assertThat(onHand(product)).as("none of the refused counts changed the shelf").isEqualTo(10);
        assertThat(ok(put(url), "{\"quantity\":11,\"allowUnknownCost\":true}").get("newOnHand").asInt()).isEqualTo(11);
    }

    @Test
    void aDeliveryAndAWriteOffAreHeldToTheSameRule() throws Exception {
        JsonNode product = newProduct();
        ok(put(stockUrl(product)), "{\"quantity\":10,\"allowUnknownCost\":true}");
        String variant = variantId(product);

        refused(post("/api/v1/stock/receive"), "{\"lines\":[{\"variantId\":\"" + variant + "\",\"qty\":\"1.5\",\"unitCost\":\"100\"}]}");
        refused(post("/api/v1/stock/receive"), "{\"lines\":[{\"variantId\":\"" + variant + "\",\"qty\":\"2000000\",\"unitCost\":\"100\"}]}");
        refused(post("/api/v1/stock/write-offs"), "{\"reasonCode\":\"DAMAGED\",\"lines\":[{\"variantId\":\"" + variant + "\",\"qty\":\"0.5\"}]}");
        refused(post("/api/v1/stock/adjust"), "{\"variantId\":\"" + variant + "\",\"qtyChange\":\"-1.25\",\"reason\":\"typo\"}");
        assertThat(onHand(product)).isEqualTo(10);

        ok(post("/api/v1/stock/receive"), "{\"lines\":[{\"variantId\":\"" + variant + "\",\"qty\":\"5\",\"unitCost\":\"100\"}]}");
        ok(post("/api/v1/stock/write-offs"), "{\"reasonCode\":\"DAMAGED\",\"lines\":[{\"variantId\":\"" + variant + "\",\"qty\":\"2\"}]}");
        assertThat(onHand(product)).isEqualTo(13);
    }

    @Test
    void aShelfWalkCountAndAPurchaseOrderFollowTheRuleToo() throws Exception {
        JsonNode product = newProduct();
        ok(put(stockUrl(product)), "{\"quantity\":10,\"allowUnknownCost\":true}");
        String variant = variantId(product);

        String countId = ok(post("/api/v1/stock/counts"), "{\"variantIds\":[\"" + variant + "\"],\"blind\":false,\"reason\":\"walk\"}").get("id").asText();
        refused(put("/api/v1/stock/counts/" + countId + "/lines/" + variant), "{\"countedQty\":9.5}");
        ok(put("/api/v1/stock/counts/" + countId + "/lines/" + variant), "{\"countedQty\":9}");

        String supplierId = ok(post("/api/v1/admin/suppliers"), "{\"name\":\"Qty Supplier " + UUID.randomUUID().toString().substring(0, 6) + "\"}").get("id").asText();
        refused(post("/api/v1/admin/purchase-orders"),
                "{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + variant + "\",\"qty\":\"2.5\",\"unitCost\":\"100\"}]}");
        JsonNode po = ok(post("/api/v1/admin/purchase-orders"),
                "{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + variant + "\",\"qty\":\"10\",\"unitCost\":\"100\"}]}");
        String poId = po.get("id").asText();
        ok(post("/api/v1/admin/purchase-orders/" + poId + "/send"), null);
        refused(post("/api/v1/admin/purchase-orders/" + poId + "/receive"),
                "{\"lines\":[{\"poLineId\":\"" + po.get("lines").get(0).get("id").asText() + "\",\"qty\":\"0.5\"}]}");
        ok(post("/api/v1/admin/purchase-orders/" + poId + "/receive"),
                "{\"lines\":[{\"poLineId\":\"" + po.get("lines").get(0).get("id").asText() + "\",\"qty\":\"4\"}]}");
    }

    @Test
    void somethingSoldByWeightMayStillBeCountedInFractionsAndAShelfThatHoldsOneCanBeCorrected() throws Exception {
        JsonNode product = newProduct();
        String url = stockUrl(product);
        ProductVariant variant = variantRepository.findById(UUID.fromString(variantId(product))).orElseThrow();
        variant.setUom("KG");
        variantRepository.save(variant);

        assertThat(ok(put(url), "{\"quantity\":2.5,\"allowUnknownCost\":true}").get("newOnHand").asDouble()).isEqualTo(2.5);

        // Re-labelled as pieces, the 2.5 on the shelf can still be put right by typing a whole number.
        variant.setUom("PIECE");
        variantRepository.save(variant);
        refused(put(url), "{\"quantity\":2.5}");
        assertThat(ok(put(url), "{\"quantity\":3,\"allowUnknownCost\":true}").get("newOnHand").asInt()).isEqualTo(3);
    }

    @Test
    void aShopperCannotPutAFractionOrAnAbsurdNumberOfAnItemInTheCart() throws Exception {
        JsonNode product = newProduct();
        ok(put(stockUrl(product)), "{\"quantity\":20,\"allowUnknownCost\":true}");
        ok(post("/api/v1/products/" + product.get("id").asText() + "/publish"), null);
        String variant = variantId(product);
        String session = UUID.randomUUID().toString();

        MvcResult fraction = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":\"" + variant + "\",\"qty\":1.5}")).andReturn();
        assertThat(fraction.getResponse().getStatus()).as(fraction.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(fraction.getResponse().getContentAsString()).contains("whole number").contains("Quantity Test");

        MvcResult absurd = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":\"" + variant + "\",\"qty\":1000000000000}")).andReturn();
        assertThat(absurd.getResponse().getStatus()).isEqualTo(400);

        MvcResult added = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":\"" + variant + "\",\"qty\":2}")).andReturn();
        assertThat(added.getResponse().getStatus()).isEqualTo(200);
        String itemId = json.readTree(added.getResponse().getContentAsString()).get("items").get(0).get("id").asText();

        MvcResult updateFraction = mvc.perform(put("/api/v1/cart/items/" + itemId).header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":2.5}")).andReturn();
        assertThat(updateFraction.getResponse().getStatus()).as(updateFraction.getResponse().getContentAsString()).isEqualTo(400);
    }
}
