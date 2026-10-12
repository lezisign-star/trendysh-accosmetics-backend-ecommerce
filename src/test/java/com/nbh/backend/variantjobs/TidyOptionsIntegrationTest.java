package com.nbh.backend.variantjobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.repository.ProductOptionValueRepository;
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

import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** "Tidy colours & sizes" (VARIANTS_PLAN.md phase 6). Product names start "0000" so they sort first on the shared database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TidyOptionsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ProductOptionValueRepository optionRows;

    private String token;

    @BeforeEach
    void signIn() {
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
        MvcResult r = call(request, body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** A product as the editor made them before variants v2: options only in the names. */
    private JsonNode product(String... variantNames) throws Exception {
        String name = "0000 Tidy " + UUID.randomUUID().toString().substring(0, 8);
        StringBuilder vs = new StringBuilder();
        for (String n : variantNames) {
            if (vs.length() > 0) vs.append(',');
            vs.append("{\"variantName\":\"").append(n.replace("{name}", name)).append("\",\"sellingPrice\":\"500\",\"isActive\":true}");
        }
        return ok(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":true,"
                + "\"variants\":[" + vs + "]}");
    }

    private Optional<JsonNode> listed(JsonNode product) throws Exception {
        JsonNode page = ok(get("/api/v1/admin/tidy-options").param("limit", "200"), null);
        return StreamSupport.stream(page.get("products").spliterator(), false)
                .filter(p -> p.get("productId").asText().equals(product.get("id").asText())).findFirst();
    }

    private static JsonNode row(JsonNode listedProduct, int i) {
        return listedProduct.get("variants").get(i);
    }

    @Test
    void eachVariantGetsASuggestionFromItsNameAndUnknownOnesAreLeftForStaff() throws Exception {
        JsonNode p = product("{name} – Ruby Red / 50ml", "Nude Beige", "Large", "Pack of 3");
        JsonNode listed = listed(p).orElseThrow();

        assertThat(row(listed, 0).get("color").asText()).isEqualTo("Ruby Red");
        assertThat(row(listed, 0).get("size").asText()).isEqualTo("50ml");
        assertThat(row(listed, 0).get("swatchHex").asText()).isEqualTo("#9B111E");
        assertThat(row(listed, 0).get("colorFamily").asText()).isEqualTo("RED");
        assertThat(row(listed, 0).get("option").asText()).isEqualTo("Ruby Red / 50ml");
        assertThat(row(listed, 1).get("color").asText()).isEqualTo("Nude Beige");
        assertThat(row(listed, 1).get("size").isNull()).isTrue();
        assertThat(row(listed, 2).get("size").asText()).isEqualTo("Large");
        assertThat(row(listed, 2).get("color").isNull()).isTrue();
        assertThat(row(listed, 3).get("color").isNull()).as("not a colour or a size").isTrue();
        assertThat(row(listed, 3).get("size").isNull()).isTrue();

        ok(post("/api/v1/admin/tidy-options/" + p.get("id").asText() + "/leave"), null); // tidy up the shared database
    }

    /** Names the shop's own data really has (seen on the QA copy). */
    @Test
    void aSizeInsideAPartIsSplitOffAndTheColourComesFromTheLastPartThatNamesOne() throws Exception {
        JsonNode p = product("Clear 100g", "Pink 100g", "Chrome Shine Gel - Red");
        JsonNode listed = listed(p).orElseThrow();

        assertThat(row(listed, 0).get("color").asText()).isEqualTo("Clear");
        assertThat(row(listed, 0).get("size").asText()).isEqualTo("100g");
        assertThat(row(listed, 0).get("colorFamily").asText()).isEqualTo("CLEAR");
        assertThat(row(listed, 1).get("color").asText()).isEqualTo("Pink");
        assertThat(row(listed, 1).get("size").asText()).isEqualTo("100g");
        assertThat(row(listed, 2).get("color").asText()).isEqualTo("Red");
        assertThat(row(listed, 2).get("size").isNull()).isTrue();

        ok(post("/api/v1/admin/tidy-options/" + p.get("id").asText() + "/leave"), null);
    }

    @Test
    void savingGivesTheVariantsTheirColoursAndSizesAndTakesTheProductOffTheList() throws Exception {
        JsonNode p = product("Ruby", "Nude");
        JsonNode listed = listed(p).orElseThrow();
        String ruby = row(listed, 0).get("variantId").asText();
        String nude = row(listed, 1).get("variantId").asText();

        JsonNode saved = ok(post("/api/v1/admin/tidy-options/" + p.get("id").asText()), "{\"variants\":["
                + "{\"variantId\":\"" + ruby + "\",\"color\":\"Ruby\",\"size\":null,\"swatchHex\":\"#9B111E\",\"colorFamily\":null},"
                + "{\"variantId\":\"" + nude + "\",\"color\":\"Nude\",\"size\":null,\"swatchHex\":\"c8a28a\",\"colorFamily\":\"nude\"}]}");

        assertThat(saved.get("saved").asInt()).isEqualTo(2);
        assertThat(listed(p)).isEmpty();
        JsonNode product = ok(get("/api/v1/products/" + p.get("id").asText()), null);
        assertThat(product.get("variants").get(1).get("optionColor").asText()).isEqualTo("Nude");
        assertThat(product.get("variants").get(1).get("swatchHex").asText()).isEqualTo("#C8A28A");
        assertThat(product.get("variants").get(1).get("colorFamily").asText()).isEqualTo("NUDE");
        assertThat(optionRows.findByProductIdOrderByKindAscPositionAsc(UUID.fromString(p.get("id").asText())).stream()
                .map(o -> o.getValue()).toList()).containsExactly("Ruby", "Nude");
    }

    @Test
    void optionsThatAreNotColoursOrSizesCanBeLeftAsTheyAre() throws Exception {
        JsonNode p = product("Pack of 3", "Pack of 6");
        assertThat(listed(p)).isPresent();

        ok(post("/api/v1/admin/tidy-options/" + p.get("id").asText() + "/leave"), null);

        assertThat(listed(p)).isEmpty();
        JsonNode product = ok(get("/api/v1/products/" + p.get("id").asText()), null);
        assertThat(product.get("variants").get(0).get("variantName").asText()).isEqualTo("Pack of 3");
        assertThat(product.get("variants").get(0).get("optionColor").isNull()).isTrue();
    }

    @Test
    void aOneVariantProductAndOneAlreadyTidyAreNotListed() throws Exception {
        JsonNode single = product("Default");
        assertThat(listed(single)).isEmpty();

        String name = "0000 Tidy " + UUID.randomUUID().toString().substring(0, 8);
        JsonNode tidy = ok(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":true,"
                + "\"variants\":[{\"variantName\":\"a\",\"sellingPrice\":\"1\",\"isActive\":true,\"options\":{\"color\":\"Red\"}},"
                + "{\"variantName\":\"b\",\"sellingPrice\":\"1\",\"isActive\":true,\"options\":{\"color\":\"Blue\"}}]}");
        assertThat(listed(tidy)).isEmpty();
    }

    @Test
    void mistakesAreRefusedAndNeedsAttentionMentionsWhatIsWaiting() throws Exception {
        JsonNode p = product("Red", "Blue");
        String red = row(listed(p).orElseThrow(), 0).get("variantId").asText();
        MvcResult bad = call(post("/api/v1/admin/tidy-options/" + p.get("id").asText()),
                "{\"variants\":[{\"variantId\":\"" + red + "\",\"color\":\"Red\",\"swatchHex\":\"#XYZ\"}]}");
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        MvcResult stranger = call(post("/api/v1/admin/tidy-options/" + p.get("id").asText()),
                "{\"variants\":[{\"variantId\":\"" + UUID.randomUUID() + "\",\"color\":\"Red\"}]}");
        assertThat(stranger.getResponse().getStatus()).isEqualTo(400);
        assertThat(listed(p)).as("nothing was saved").isPresent();

        JsonNode attention = ok(get("/api/v1/admin/attention"), null);
        assertThat(StreamSupport.stream(attention.get("items").spliterator(), false)
                .filter(i -> i.get("key").asText().equals("tidy-options")).findFirst().orElseThrow()
                .get("severity").asText()).isEqualTo("INFO");

        ok(post("/api/v1/admin/tidy-options/" + p.get("id").asText() + "/leave"), null);
    }
}
