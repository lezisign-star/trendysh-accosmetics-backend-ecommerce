package com.nbh.backend.variantjobs;

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
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Reusing the shop's colours (owner, 2026-10-13): the colour library, copying one product's colours and sizes, and the
 * shop's own dot winning when a colour name is typed again. Colour names carry a per-run tag so the shared database's
 * other colours don't get in the way.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ColourLibraryIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;

    private String token;
    private final String tag = UUID.randomUUID().toString().substring(0, 6);

    @BeforeEach
    void signIn() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult r = mvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String content = r.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private static String v(String name, String color, String size, String hex) {
        return "{\"variantName\":\"" + name + "\",\"sellingPrice\":\"500\",\"isActive\":true,\"options\":{\"color\":"
                + (color == null ? "null" : "\"" + color + "\"") + ",\"size\":" + (size == null ? "null" : "\"" + size + "\"")
                + ",\"swatchHex\":" + (hex == null ? "null" : "\"" + hex + "\"") + "}}";
    }

    private JsonNode product(String name, String... variants) throws Exception {
        return ok(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":true,"
                + "\"variants\":[" + String.join(",", variants) + "]}");
    }

    @Test
    void theLibraryListsEachColourOnceWithItsUsualDotMostUsedFirst() throws Exception {
        product("Gel A " + tag, v("a", "Ruby " + tag, null, "#9B111E"), v("b", "Nude " + tag, null, "#C8A28A"));
        product("Gel B " + tag, v("a", "ruby " + tag, null, "#9B111E"), v("b", "Coral " + tag, null, "#F2705E"));

        JsonNode lib = ok(get("/api/v1/admin/variant-jobs/colour-library").param("q", tag), null);

        assertThat(StreamSupport.stream(lib.spliterator(), false)
                .map(c -> c.get("value").asText() + "|" + c.get("swatchHex").asText() + "|" + c.get("colorFamily").asText()
                        + "|" + c.get("products").asInt()).toList())
                .as("Ruby is on two products, so first; one entry whatever its case")
                .startsWith("Ruby " + tag + "|#9B111E|RED|2")
                .contains("Nude " + tag + "|#C8A28A|NUDE|1", "Coral " + tag + "|#F2705E|CORAL|1")
                .hasSize(3);
    }

    @Test
    void aProductsColoursAndSizesCanBeCopiedInItsOwnOrder() throws Exception {
        String name = "Bonnet Set " + tag;
        product(name, v("a", "Wine " + tag, "S", "#6B1630"), v("b", "Wine " + tag, "M", "#6B1630"),
                v("c", "Black " + tag, "S", "#1F1A1C"));

        JsonNode sets = ok(get("/api/v1/admin/variant-jobs/option-sets").param("q", name), null);

        assertThat(sets).hasSize(1);
        JsonNode set = sets.get(0);
        assertThat(set.get("productName").asText()).isEqualTo(name);
        assertThat(StreamSupport.stream(set.get("colors").spliterator(), false)
                .map(c -> c.get("value").asText() + "|" + c.get("swatchHex").asText()).toList())
                .containsExactly("Wine " + tag + "|#6B1630", "Black " + tag + "|#1F1A1C");
        assertThat(StreamSupport.stream(set.get("sizes").spliterator(), false).map(s -> s.get("value").asText()).toList())
                .containsExactly("S", "M");
    }

    @Test
    void typingAColourTheShopAlreadyUsesGivesTheSameDot() throws Exception {
        product("Lip Oil " + tag, v("a", "Berry " + tag, null, "#5A1F3D"), v("b", "Plain", null, null));

        JsonNode s = ok(post("/api/v1/admin/variant-jobs/suggest-swatches"), "{\"names\":[\"berry " + tag + "\"]}");

        assertThat(s.get(0).get("source").asText()).isEqualTo("SHOP");
        assertThat(s.get(0).get("hex").asText()).isEqualTo("#5A1F3D");
    }

    @Test
    void aRemovedProductsColoursAreNotOffered() throws Exception {
        JsonNode p = product("Gone " + tag, v("a", "Teal " + tag, null, "#008080"), v("b", "Mint " + tag, null, "#98E0B8"));
        ok(delete("/api/v1/products/" + p.get("id").asText()), null);

        assertThat(ok(get("/api/v1/admin/variant-jobs/colour-library").param("q", tag), null)).isEmpty();
        assertThat(ok(get("/api/v1/admin/variant-jobs/option-sets").param("q", "Gone " + tag), null)).isEmpty();
    }
}
