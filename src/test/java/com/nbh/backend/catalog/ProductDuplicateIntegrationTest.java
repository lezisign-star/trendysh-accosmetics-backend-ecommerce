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

/**
 * {@code POST /products/{id}/duplicate} (Phase 3, 2026-09-24) — fast entry of many similar
 * products, a founder priority from the original UX audit. Unique names per run so it can share
 * the database with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductDuplicateIntegrationTest {

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

    private String newCategory(String name) throws Exception {
        return ok(post("/api/v1/categories"), "{\"name\":\"" + name + "\"}").get("id").asText();
    }

    @Test
    void duplicateCopiesEverythingAsAnUnpublishedDraftWithNewSkus() throws Exception {
        String cat = newCategory(unique("Nails"));
        String name = unique("Glitter Gel");
        JsonNode source = ok(post("/api/v1/products/draft"), "{"
                + "\"name\":\"" + name + "\","
                + "\"description\":\"sparkly finish\","
                + "\"categoryIds\":[\"" + cat + "\"],"
                + "\"published\":true,"
                + "\"variants\":["
                + "{\"variantName\":\"Silver\",\"description\":\"cool tone\",\"sellingPrice\":\"400\",\"isActive\":true},"
                + "{\"variantName\":\"Gold\",\"sellingPrice\":\"420\",\"isActive\":true}"
                + "]}");
        String sourceId = source.get("id").asText();
        String silverSku = source.get("variants").get(0).get("sku").asText();
        String goldSku = source.get("variants").get(1).get("sku").asText();

        JsonNode copy = ok(post("/api/v1/products/" + sourceId + "/duplicate"), null);

        assertThat(copy.get("id").asText()).isNotEqualTo(sourceId);
        assertThat(copy.get("name").asText()).isEqualTo(name + " (copy)");
        assertThat(copy.get("description").asText()).isEqualTo("sparkly finish");
        assertThat(copy.get("isPublished").asBoolean()).isFalse();
        assertThat(copy.get("categories")).hasSize(1);
        assertThat(copy.get("categories").get(0).get("id").asText()).isEqualTo(cat);

        assertThat(copy.get("variants")).hasSize(2);
        JsonNode copiedSilver = copy.get("variants").get(0);
        assertThat(copiedSilver.get("variantName").asText()).isEqualTo("Silver");
        assertThat(copiedSilver.get("description").asText()).isEqualTo("cool tone");
        assertThat(copiedSilver.get("sellingPrice").asDouble()).isEqualTo(400.0);
        // A copy must never reuse the original's SKUs — they're unique across the whole catalogue.
        assertThat(copiedSilver.get("sku").asText()).isNotEqualTo(silverSku);
        assertThat(copy.get("variants").get(1).get("sku").asText()).isNotEqualTo(goldSku);

        // The original is completely untouched by duplicating it.
        JsonNode original = ok(get("/api/v1/products/" + sourceId), null);
        assertThat(original.get("name").asText()).isEqualTo(name);
        assertThat(original.get("isPublished").asBoolean()).isTrue();
        assertThat(original.get("variants")).hasSize(2);
    }

    @Test
    void duplicatingAProductWithNoCategoriesOrVariantsStillWorks() throws Exception {
        JsonNode source = ok(post("/api/v1/products/draft"), "{\"name\":\"" + unique("Bare Draft")
                + "\",\"categoryIds\":[],\"published\":false,\"variants\":[]}");

        JsonNode copy = ok(post("/api/v1/products/" + source.get("id").asText() + "/duplicate"), null);
        assertThat(copy.get("categories")).isEmpty();
        assertThat(copy.get("variants")).isEmpty();
        assertThat(copy.get("isPublished").asBoolean()).isFalse();
    }
}
