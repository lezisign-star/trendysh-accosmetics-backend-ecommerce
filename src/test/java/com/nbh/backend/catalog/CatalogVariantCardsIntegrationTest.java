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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * {@code GET /catalog/variants} and {@code /catalog/variants/{id}} — the storefront's one-
 * card-per-variant browse (Phase 3, 2026-09-24). No auth on the read side (public storefront);
 * a staff token is only used to set up fixture products through the admin API. Unique names per
 * run so it can share the database with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogVariantCardsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;

    private String staffToken;

    @BeforeEach
    void signInAsSuperAdmin() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode asStaff(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    /** Public, unauthenticated — exactly what a shopper's browser sends. */
    private JsonNode asShopper(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String newCategory(String name, String parentId) throws Exception {
        String body = "{\"name\":\"" + name + "\"" + (parentId != null ? ",\"parentId\":\"" + parentId + "\"" : "") + "}";
        return asStaff(post("/api/v1/categories"), body).get("id").asText();
    }

    private JsonNode newDraft(String name, String categoryId, boolean published, String variantsJson) throws Exception {
        String body = "{\"name\":\"" + name + "\","
                + "\"categoryIds\":" + (categoryId != null ? "[\"" + categoryId + "\"]" : "[]") + ","
                + "\"published\":" + published + ","
                + "\"variants\":" + variantsJson + "}";
        return asStaff(post("/api/v1/products/draft"), body);
    }

    private void giveStock(String productId, String variantId, int qty) throws Exception {
        asStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock"), "{\"quantity\":" + qty + "}");
    }

    @Test
    void unpublishedHiddenDeletedAndPricelessVariantsNeverAppear() throws Exception {
        String q = unique("Card Visibility Product");

        JsonNode unpublished = newDraft(q + " Unpublished", null, false,
                "[{\"variantName\":\"Only\",\"sellingPrice\":\"100\",\"isActive\":true}]");
        JsonNode hiddenProduct = newDraft(q + " Hidden Variant", null, true,
                "[{\"variantName\":\"Visible\",\"sellingPrice\":\"100\",\"isActive\":true},"
                        + "{\"variantName\":\"Hidden\",\"sellingPrice\":\"100\",\"isActive\":false}]");
        String visibleId = hiddenProduct.get("variants").get(0).get("id").asText();
        String hiddenId = hiddenProduct.get("variants").get(1).get("id").asText();

        JsonNode priceless = newDraft(q + " Priceless Variant", null, true,
                "[{\"variantName\":\"Has Price\",\"sellingPrice\":\"100\",\"isActive\":true},"
                        + "{\"variantName\":\"No Price\",\"isActive\":true}]");
        String hasPriceId = priceless.get("variants").get(0).get("id").asText();
        String noPriceId = priceless.get("variants").get(1).get("id").asText();

        // A deleted variant: save it in, then re-save without it (soft-deletes, same as Phase 3a's editor test).
        JsonNode willDelete = newDraft(q + " Deleted Variant", null, true,
                "[{\"variantName\":\"Stays\",\"sellingPrice\":\"100\",\"isActive\":true},"
                        + "{\"variantName\":\"Goes\",\"sellingPrice\":\"100\",\"isActive\":true}]");
        String staysId = willDelete.get("variants").get(0).get("id").asText();
        String goesId = willDelete.get("variants").get(1).get("id").asText();
        asStaff(put("/api/v1/products/" + willDelete.get("id").asText() + "/full"), "{"
                + "\"expectedUpdatedAt\":\"" + willDelete.get("updatedAt").asText() + "\","
                + "\"name\":\"" + willDelete.get("name").asText() + "\",\"categoryIds\":[],\"published\":true,"
                + "\"variants\":[{\"id\":\"" + staysId + "\",\"variantName\":\"Stays\",\"sellingPrice\":\"100\",\"isActive\":true}]}");

        JsonNode results = asShopper(get("/api/v1/catalog/variants").param("q", q).param("size", "50"));
        java.util.List<String> variantIds = new java.util.ArrayList<>();
        results.get("content").forEach(c -> variantIds.add(c.get("variantId").asText()));

        // Never appear: any variant of the unpublished product, the inactive/hidden variant, the
        // priceless variant, or the soft-deleted variant.
        assertThat(variantIds).noneMatch(id -> id.equals(unpublished.get("variants").get(0).get("id").asText()));
        assertThat(variantIds).doesNotContain(hiddenId, noPriceId, goesId);
        // The ones that SHOULD appear are actually there — not just "nothing bad appeared".
        assertThat(variantIds).contains(visibleId, hasPriceId, staysId);
    }

    @Test
    void aProductInTwoCategoriesShowsUpUnderBothAndTheirSharedParent() throws Exception {
        String parent = newCategory(unique("Skincare"), null);
        String sub = newCategory(unique("Serums"), parent);
        String other = newCategory(unique("Gifts"), null);

        JsonNode product = newDraft(unique("Two Category Product"), null, true,
                "[{\"variantName\":\"Only\",\"sellingPrice\":\"500\",\"isActive\":true}]");
        // The draft helper only sets one category; add the second directly via full save.
        asStaff(put("/api/v1/products/" + product.get("id").asText() + "/full"), "{"
                + "\"expectedUpdatedAt\":\"" + product.get("updatedAt").asText() + "\","
                + "\"name\":\"" + product.get("name").asText() + "\","
                + "\"categoryIds\":[\"" + sub + "\",\"" + other + "\"],\"published\":true,"
                + "\"variants\":[{\"id\":\"" + product.get("variants").get(0).get("id").asText()
                + "\",\"variantName\":\"Only\",\"sellingPrice\":\"500\",\"isActive\":true}]}");
        String productId = product.get("id").asText();

        assertThat(asShopper(get("/api/v1/catalog/variants").param("category", subSlug(sub))).get("content"))
                .anyMatch(c -> c.get("productId").asText().equals(productId));
        assertThat(asShopper(get("/api/v1/catalog/variants").param("category", subSlug(parent))).get("content"))
                .anyMatch(c -> c.get("productId").asText().equals(productId));
        assertThat(asShopper(get("/api/v1/catalog/variants").param("category", subSlug(other))).get("content"))
                .anyMatch(c -> c.get("productId").asText().equals(productId));
    }

    private String subSlug(String categoryId) throws Exception {
        JsonNode categories = asStaff(get("/api/v1/categories"), null);
        for (JsonNode c : categories) {
            if (c.get("id").asText().equals(categoryId)) return c.get("slug").asText();
        }
        throw new IllegalStateException("category not found: " + categoryId);
    }

    @Test
    void theSiblingListAndNameHidingRuleAreCorrect() throws Exception {
        String q = unique("Sibling Product");
        JsonNode product = newDraft(q, null, true,
                "[{\"variantName\":\"Red\",\"sellingPrice\":\"400\",\"isActive\":true},"
                        + "{\"variantName\":\"Blue\",\"sellingPrice\":\"420\",\"isActive\":true},"
                        + "{\"variantName\":\"Green\",\"sellingPrice\":\"440\",\"isActive\":true}]");
        String redId = product.get("variants").get(0).get("id").asText();
        String blueId = product.get("variants").get(1).get("id").asText();

        JsonNode detail = asShopper(get("/api/v1/catalog/variants/" + redId));
        assertThat(detail.get("card").get("variantName").asText()).isEqualTo("Red");
        assertThat(detail.get("siblings")).hasSize(3);
        assertThat(detail.get("siblings")).anyMatch(s -> s.get("variantId").asText().equals(redId));
        assertThat(detail.get("siblings")).anyMatch(s -> s.get("variantId").asText().equals(blueId));
        assertThat(detail.get("card").get("siblingCount").asInt()).isEqualTo(2);

        // A single-sellable-variant product hides its variant name entirely.
        JsonNode solo = newDraft(unique("Solo Product"), null, true,
                "[{\"variantName\":\"Default\",\"sellingPrice\":\"250\",\"isActive\":true}]");
        String soloVariantId = solo.get("variants").get(0).get("id").asText();
        JsonNode soloDetail = asShopper(get("/api/v1/catalog/variants/" + soloVariantId));
        assertThat(soloDetail.get("card").get("variantName").isNull()).isTrue();
        assertThat(soloDetail.get("card").get("siblingCount").asInt()).isZero();
    }
}
