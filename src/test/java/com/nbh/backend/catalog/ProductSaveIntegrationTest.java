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
 * The Phase 3 one-page editor's single Save ({@code POST /products/draft} and
 * {@code PUT /products/{id}/full}) — the API the plain-variant/multi-category redesign
 * actually shipped for (2026-09-24). Unique names per run so it can share the database
 * with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductSaveIntegrationTest {

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

    private int status(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request, body).getResponse().getStatus();
    }

    private String newCategory(String name, String parentId) throws Exception {
        String body = "{\"name\":\"" + name + "\"" + (parentId != null ? ",\"parentId\":\"" + parentId + "\"" : "") + "}";
        return ok(post("/api/v1/categories"), body).get("id").asText();
    }

    @Test
    void aBlankDraftSaves() throws Exception {
        JsonNode draft = ok(post("/api/v1/products/draft"), "{\"published\":false,\"categoryIds\":[],\"variants\":[]}");
        assertThat(draft.get("id").asText()).isNotBlank();
        assertThat(draft.get("isPublished").asBoolean()).isFalse();
        assertThat(draft.get("deleted").asBoolean()).isFalse();
        // Nothing filled in — every readiness hint fires, none of it blocks the save.
        assertThat(draft.get("warnings")).anyMatch(w -> w.asText().equals("No name yet"));
        assertThat(draft.get("warnings")).anyMatch(w -> w.asText().equals("Not in any category"));
        assertThat(draft.get("warnings")).anyMatch(w -> w.asText().equals("No photo"));
    }

    @Test
    void theWholeProductRoundTripsThroughASingleSave() throws Exception {
        String cat = newCategory(unique("Nails"), null);
        String name = unique("Chrome Gel");
        JsonNode draft = ok(post("/api/v1/products/draft"), "{"
                + "\"name\":\"" + name + "\","
                + "\"description\":\"a chrome finish gel\","
                + "\"categoryIds\":[\"" + cat + "\"],"
                + "\"published\":false,"
                + "\"variants\":["
                + "{\"variantName\":\"Ruby Red\",\"description\":\"deep red\",\"sellingPrice\":\"450\",\"isActive\":true},"
                + "{\"variantName\":\"Ocean Blue\",\"sellingPrice\":\"475\",\"isActive\":true}"
                + "]}");

        assertThat(draft.get("name").asText()).isEqualTo(name);
        assertThat(draft.get("categories")).hasSize(1);
        assertThat(draft.get("variants")).hasSize(2);
        JsonNode red = draft.get("variants").get(0);
        assertThat(red.get("variantName").asText()).isEqualTo("Ruby Red");
        assertThat(red.get("description").asText()).isEqualTo("deep red");
        assertThat(red.get("sellingPrice").asDouble()).isEqualTo(450.0);
        assertThat(red.get("sku").asText()).isNotBlank();
        // Every readiness hint about missing fields is gone once they're filled in.
        assertThat(draft.get("warnings")).noneMatch(w -> w.asText().contains("name"));
        assertThat(draft.get("warnings")).noneMatch(w -> w.asText().contains("price"));
        assertThat(draft.get("warnings")).noneMatch(w -> w.asText().contains("category"));
    }

    @Test
    void removingAVariantFromTheListSoftDeletesItRatherThanLeavingItVisible() throws Exception {
        String cat = newCategory(unique("Lashes"), null);
        JsonNode draft = ok(post("/api/v1/products/draft"), "{"
                + "\"name\":\"" + unique("Lash Set") + "\","
                + "\"categoryIds\":[\"" + cat + "\"],"
                + "\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Natural\",\"sellingPrice\":\"300\",\"isActive\":true},"
                + "{\"variantName\":\"Dramatic\",\"sellingPrice\":\"350\",\"isActive\":true}]}");
        String id = draft.get("id").asText();
        String updatedAt = draft.get("updatedAt").asText();
        String keptVariantId = draft.get("variants").get(0).get("id").asText();

        // Save again with only the first variant in the list — the second must disappear, not error.
        JsonNode saved = ok(put("/api/v1/products/" + id + "/full"), "{"
                + "\"expectedUpdatedAt\":\"" + updatedAt + "\","
                + "\"name\":\"" + draft.get("name").asText() + "\","
                + "\"categoryIds\":[\"" + cat + "\"],"
                + "\"published\":false,"
                + "\"variants\":[{\"id\":\"" + keptVariantId + "\",\"variantName\":\"Natural\",\"sellingPrice\":\"300\",\"isActive\":true}]}");

        assertThat(saved.get("variants")).hasSize(1);
        assertThat(saved.get("variants").get(0).get("id").asText()).isEqualTo(keptVariantId);

        // A fresh read confirms it, not just the save response.
        JsonNode reread = ok(get("/api/v1/products/" + id), null);
        assertThat(reread.get("variants")).hasSize(1);
    }

    @Test
    void aStaleExpectedUpdatedAtIsRejectedAsAConflict() throws Exception {
        JsonNode draft = ok(post("/api/v1/products/draft"), "{\"name\":\"" + unique("Conflict Test")
                + "\",\"categoryIds\":[],\"published\":false,\"variants\":[]}");
        String id = draft.get("id").asText();

        int code = status(put("/api/v1/products/" + id + "/full"), "{"
                + "\"expectedUpdatedAt\":\"2020-01-01T00:00:00Z\","
                + "\"name\":\"Should not apply\",\"categoryIds\":[],\"published\":false,\"variants\":[]}");
        assertThat(code).isEqualTo(409);

        // The product itself is untouched by the rejected write.
        JsonNode reread = ok(get("/api/v1/products/" + id), null);
        assertThat(reread.get("name").asText()).isEqualTo(draft.get("name").asText());
    }

    @Test
    void aProductInSeveralCategoriesShowsUpUnderEachOneAndTheirSharedParent() throws Exception {
        String parent = newCategory(unique("Skincare"), null);
        String sub = newCategory(unique("Serums"), parent);
        String unrelated = newCategory(unique("Tools"), null);

        JsonNode draft = ok(post("/api/v1/products/draft"), "{"
                + "\"name\":\"" + unique("Vitamin C Serum") + "\","
                + "\"categoryIds\":[\"" + sub + "\",\"" + unrelated + "\"],"
                + "\"published\":true,"
                + "\"variants\":[{\"variantName\":\"30ml\",\"sellingPrice\":\"1200\",\"isActive\":true}]}");
        String id = draft.get("id").asText();

        assertThat(ok(get("/api/v1/products?categoryId=" + sub), null).get("content"))
                .anyMatch(p -> p.get("id").asText().equals(id));
        // Filtering by the subcategory's parent also finds it (rollup).
        assertThat(ok(get("/api/v1/products?categoryId=" + parent), null).get("content"))
                .anyMatch(p -> p.get("id").asText().equals(id));
        assertThat(ok(get("/api/v1/products?categoryId=" + unrelated), null).get("content"))
                .anyMatch(p -> p.get("id").asText().equals(id));
    }

    @Test
    void publishingWithAMissingFieldSucceedsAndOnlyWarns() throws Exception {
        JsonNode draft = ok(post("/api/v1/products/draft"), "{"
                + "\"name\":\"" + unique("No Photo Product") + "\","
                + "\"categoryIds\":[],"
                + "\"published\":true,"
                + "\"variants\":[{\"variantName\":\"Only\",\"sellingPrice\":\"100\",\"isActive\":true}]}");

        assertThat(draft.get("isPublished").asBoolean()).isTrue();
        assertThat(draft.get("warnings")).anyMatch(w -> w.asText().equals("No photo"));
        assertThat(draft.get("warnings")).anyMatch(w -> w.asText().equals("Not in any category"));
    }
}
