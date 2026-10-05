package com.nbh.backend.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.stock.repository.StockBalanceRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Stock for a small shop (2026-10-05): staff type a number on the stock list or in a product card's quick
 * edit instead of going through receipts and counts. This covers what that needs from the API: a typed
 * number must work for a product whose cost nobody ever entered (an explicit opt-in, so the product
 * editor's refusal stays), a kit must refuse stock of its own, and the stock list must show each row's
 * product, leave out deleted products, and narrow to what is out of stock or to one product.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SimpleStockEditingIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private StockBalanceRepository balanceRepository;

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

    private int status(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request, body).getResponse().getStatus();
    }

    /** A draft product with one variant and no cost anywhere. */
    private JsonNode newProduct(String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"isActive\":true}]}";
        return ok(post("/api/v1/products/draft"), body);
    }

    private static String stockUrl(JsonNode product) {
        return "/api/v1/products/" + product.get("id").asText() + "/variants/"
                + product.get("variants").get(0).get("id").asText() + "/stock";
    }

    private void setStock(JsonNode product, int quantity) throws Exception {
        ok(put(stockUrl(product)), "{\"quantity\":" + quantity + ",\"allowUnknownCost\":true}");
    }

    @Test
    void aTypedStockNumberWorksEvenWhenNoCostIsKnown() throws Exception {
        JsonNode product = newProduct(unique("Cost-free stock"));
        String url = stockUrl(product);

        // The default is still the product editor's stance: a gain that cannot be valued is refused.
        assertThat(status(put(url), "{\"quantity\":12}")).isEqualTo(422);

        JsonNode up = ok(put(url), "{\"quantity\":12,\"allowUnknownCost\":true,\"reason\":\"Stock list edit\"}");
        assertThat(up.get("previousOnHand").asInt()).isZero();
        assertThat(up.get("newOnHand").asInt()).isEqualTo(12);
        assertThat(up.get("documentNo").asText()).isNotBlank();

        // Raising it again still has no cost to use (the average is 0), so it needs the opt-in again.
        assertThat(status(put(url), "{\"quantity\":20}")).isEqualTo(422);
        assertThat(ok(put(url), "{\"quantity\":20,\"allowUnknownCost\":true}").get("newOnHand").asInt())
                .isEqualTo(20);

        // Lowering never needs a cost.
        assertThat(ok(put(url), "{\"quantity\":3}").get("newOnHand").asInt()).isEqualTo(3);

        JsonNode row = ok(get("/api/v1/stock/inventory").param("productId", product.get("id").asText()), null)
                .get("content").get(0);
        assertThat(row.get("onHandQty").asInt()).isEqualTo(3);
    }

    @Test
    void aKitHoldsNoStockOfItsOwn() throws Exception {
        JsonNode product = newProduct(unique("Kit stock"));
        UUID variantId = UUID.fromString(product.get("variants").get(0).get("id").asText());
        ProductVariant variant = variantRepository.findById(variantId).orElseThrow();
        variant.setKit(true);
        variantRepository.save(variant);

        assertThat(status(put(stockUrl(product)), "{\"quantity\":5,\"allowUnknownCost\":true}")).isEqualTo(422);

        JsonNode row = ok(get("/api/v1/stock/inventory").param("productId", product.get("id").asText()), null)
                .get("content").get(0);
        assertThat(row.get("kit").asBoolean()).as("the list marks it so the screen can leave it uneditable").isTrue();
    }

    @Test
    void theStockListHidesDeletedProductsKeepsDraftsAndCanBeNarrowed() throws Exception {
        String tag = unique("Stocklist");
        JsonNode live = newProduct(tag + " live");
        JsonNode empty = newProduct(tag + " empty");
        JsonNode gone = newProduct(tag + " gone");
        setStock(live, 7);
        setStock(gone, 3);
        ok(delete("/api/v1/products/" + gone.get("id").asText()), null);

        // A deleted product keeps its variants for the history but no longer clutters the list; drafts stay.
        JsonNode list = ok(get("/api/v1/stock/inventory").param("size", "100").param("q", tag), null).get("content");
        assertThat(list.findValuesAsText("productName")).containsExactlyInAnyOrder(tag + " live", tag + " empty");
        for (JsonNode row : list) {
            assertThat(row.get("productId").asText()).isNotBlank();
            assertThat(row.get("kit").asBoolean()).isFalse();
        }

        // Only what has nothing on hand.
        JsonNode out = ok(get("/api/v1/stock/inventory").param("size", "100").param("q", tag)
                .param("outOfStockOnly", "true"), null).get("content");
        assertThat(out.findValuesAsText("productName")).containsExactly(tag + " empty");

        // One product's variants (what the product card's quick edit asks for).
        JsonNode one = ok(get("/api/v1/stock/inventory").param("productId", live.get("id").asText()), null)
                .get("content");
        assertThat(one).hasSize(1);
        assertThat(one.get(0).get("onHandQty").asInt()).isEqualTo(7);
        assertThat(one.get(0).get("productId").asText()).isEqualTo(live.get("id").asText());
        assertThat(empty.get("id").asText()).isNotEqualTo(live.get("id").asText());
    }

    @Test
    void aDeletedProductsVariantsAreNotCountedAsLow() throws Exception {
        long before = balanceRepository.countBelowReorderLevel();
        JsonNode product = newProduct(unique("Low and gone"));
        UUID variantId = UUID.fromString(product.get("variants").get(0).get("id").asText());
        ProductVariant variant = variantRepository.findById(variantId).orElseThrow();
        variant.setReorderLevel(new BigDecimal("5"));
        variantRepository.save(variant);
        assertThat(balanceRepository.countBelowReorderLevel()).isEqualTo(before + 1);

        ok(delete("/api/v1/products/" + product.get("id").asText()), null);
        assertThat(balanceRepository.countBelowReorderLevel()).isEqualTo(before);
    }
}
