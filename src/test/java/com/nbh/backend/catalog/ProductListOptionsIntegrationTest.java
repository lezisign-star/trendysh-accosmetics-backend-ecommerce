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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The staff lists split (owner, 2026-10-17): Products shows products with one variant, Variants the ranges with two or
 * more ({@code options=SINGLE|RANGE}); no filter lists both.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductListOptionsIntegrationTest {

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

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String product(String name, String... variants) throws Exception {
        StringBuilder v = new StringBuilder();
        for (String n : variants) {
            if (v.length() > 0) v.append(',');
            v.append("{\"variantName\":\"").append(n).append("\",\"sellingPrice\":\"100\",\"isActive\":true}");
        }
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,\"variants\":[" + v + "]}";
        return ok(post("/api/v1/products/draft"), body).get("id").asText();
    }

    private List<String> list(String q, String options) throws Exception {
        var request = get("/api/v1/products").param("q", q).param("size", "50");
        if (options != null) request.param("options", options);
        List<String> ids = new ArrayList<>();
        ok(request, null).get("content").forEach(p -> ids.add(p.get("id").asText()));
        return ids;
    }

    @Test
    void oneVariantIsSavedOnItsOwnAndTheOthersAreLeftAlone() throws Exception {
        String w = "zqone" + UUID.randomUUID().toString().replaceAll("[^a-f]", "").substring(0, 6);
        String id = product("Range " + w, "Red", "Blue");
        JsonNode before = ok(get("/api/v1/products/" + id), null).get("variants");
        String red = before.get(0).get("id").asText();
        String blueName = before.get(1).get("variantName").asText();

        JsonNode saved = ok(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/products/" + id + "/variants/" + red),
                "{\"variantName\":\"Ruby Red\",\"description\":\"Deep red\",\"sellingPrice\":\"150\",\"isActive\":true,"
                        + "\"options\":{\"color\":\"Ruby Red\",\"swatchHex\":\"#9B111E\"}}");
        assertThat(saved.get("variantName").asText()).isEqualTo("Ruby Red");
        assertThat(saved.get("sellingPrice").decimalValue()).isEqualByComparingTo("150");

        JsonNode after = ok(get("/api/v1/products/" + id), null).get("variants");
        assertThat(after).hasSize(2);
        assertThat(after.get(1).get("variantName").asText()).isEqualTo(blueName);
        assertThat(after.get(0).get("optionColor").asText()).isEqualTo("Ruby Red");
    }

    @Test
    void noPhotoListsOnlyProductsWithoutAnyPhoto() throws Exception {
        String w = "zqnph" + UUID.randomUUID().toString().replaceAll("[^a-f]", "").substring(0, 6);
        String bare = product("Bare " + w, "Only");
        String withPhoto = product("Pictured " + w, "Only");
        String variant = ok(get("/api/v1/products/" + withPhoto), null).get("variants").get(0).get("id").asText();
        ok(post("/api/v1/products/" + withPhoto + "/images"),
                "{\"url\":\"https://res.cloudinary.com/test/" + w + ".png\",\"isPrimary\":true,\"variantId\":\"" + variant + "\"}");

        var request = get("/api/v1/products").param("q", w).param("noPhoto", "true").param("size", "50");
        List<String> ids = new ArrayList<>();
        ok(request, null).get("content").forEach(p -> ids.add(p.get("id").asText()));
        assertThat(ids).containsExactly(bare);
    }

    @Test
    void productsWithOneVariantAndRangesAreListedApart() throws Exception {
        String w = "zqopt" + UUID.randomUUID().toString().replaceAll("[^a-f]", "").substring(0, 6);
        String single = product("Single " + w, "Only");
        String range = product("Range " + w, "Red", "Blue");

        assertThat(list(w, "SINGLE")).containsExactly(single);
        assertThat(list(w, "RANGE")).containsExactly(range);
        assertThat(list(w, null)).containsExactlyInAnyOrder(single, range);
    }
}
