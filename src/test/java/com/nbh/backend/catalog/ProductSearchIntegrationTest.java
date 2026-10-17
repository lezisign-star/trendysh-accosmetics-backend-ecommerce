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
 * The staff product list's search box (2026-10-17): every word anywhere in the product, marks and spaces ignored,
 * option colours / codes / categories searched, best matches first, and the list's filters still applied. Each test
 * uses its own random word so it can share the database with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductSearchIntegrationTest {

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

    /** A word of letters only that no other product has. */
    private static String word() {
        StringBuilder sb = new StringBuilder("zq");
        for (char ch : UUID.randomUUID().toString().replace("-", "").substring(0, 8).toCharArray()) {
            sb.append(Character.isDigit(ch) ? (char) ('g' + (ch - '0')) : ch);
        }
        return sb.toString();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String product(String name, String categoryId, String variantName, String color) throws Exception {
        String options = color == null ? "" : ",\"options\":{\"color\":\"" + color + "\"}";
        String body = "{\"name\":\"" + name + "\","
                + "\"categoryIds\":" + (categoryId != null ? "[\"" + categoryId + "\"]" : "[]") + ","
                + "\"published\":false,"
                + "\"variants\":[{\"variantName\":\"" + variantName + "\",\"sellingPrice\":\"100\",\"isActive\":true"
                + options + "}]}";
        return ok(post("/api/v1/products/draft"), body).get("id").asText();
    }

    private List<String> search(String q, String extra) throws Exception {
        var request = get("/api/v1/products").param("q", q).param("size", "50");
        if (extra != null) request.param(extra.split("=")[0], extra.split("=")[1]);
        List<String> ids = new ArrayList<>();
        ok(request, null).get("content").forEach(p -> ids.add(p.get("id").asText()));
        return ids;
    }

    @Test
    void everyWordIsFoundAnywhereAndMarksAndSpacesDontMatter() throws Exception {
        String w = word();
        String gel = product("Roniki Gel " + w, null, "Hot Pink PU30", "Hot Pink PU30");

        assertThat(search(w + " pink", null)).containsExactly(gel);
        assertThat(search("pink " + w, null)).containsExactly(gel);
        assertThat(search(w + " pu 30", null)).containsExactly(gel);
        assertThat(search(w + " PU-30", null)).containsExactly(gel);
        assertThat(search(w + " hotpink", null)).containsExactly(gel);
        assertThat(search(w + " blue", null)).isEmpty();
    }

    @Test
    void aShortWordIsNotFoundAcrossASpace() throws Exception {
        String w = word();
        String bare = product("Bare Draft " + w, null, "Only", null);
        String red = product("Lip " + w, null, "Ruby Red", "Ruby Red");

        // "bare draft" with its space taken out holds "red"; only the real Ruby Red is found
        assertThat(search(w + " red", null)).containsExactly(red);
        assertThat(search(w + " baredraft", null)).containsExactly(bare);
    }

    @Test
    void aCategoryNameFindsItsProducts() throws Exception {
        String w = word();
        String category = ok(post("/api/v1/categories"), "{\"name\":\"" + w + " Nails\"}").get("id").asText();
        String inIt = product("Plain Tips", category, "Only", null);

        assertThat(search(w, null)).containsExactly(inIt);
        assertThat(search(w + " tips", null)).containsExactly(inIt);
    }

    @Test
    void theNameMatchComesBeforeAnOptionMatchEvenWhenOlder() throws Exception {
        String w = word();
        String byName = product(w + " Lipstick", null, "Only", null);
        String byOption = product("Cream", null, "Shade " + w, "Shade " + w);

        assertThat(search(w, null)).containsExactly(byName, byOption);
    }

    @Test
    void theListsFiltersStillApply() throws Exception {
        String w = word();
        product("Filtered " + w, null, "Only", null);

        assertThat(search(w, null)).hasSize(1);
        assertThat(search(w, "published=true")).isEmpty();
    }

    @Test
    void onlyMarksIsNoSearchAtAll() throws Exception {
        var request = get("/api/v1/products").param("q", " - ").param("size", "1");
        assertThat(ok(request, null).get("totalElements").asLong()).isPositive();
    }
}
