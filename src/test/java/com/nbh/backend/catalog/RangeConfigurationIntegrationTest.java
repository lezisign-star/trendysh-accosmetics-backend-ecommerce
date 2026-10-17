package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
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

import java.util.*;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * A range's configuration (owner, 2026-10-17): there is no main product, only the configuration the variants come from.
 * It forces the common part of every variant's name, shares price, size, description and category unless a switch lets
 * them differ, asks about variants with their own value, and changes nothing until it is confirmed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RangeConfigurationIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;

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
        String content = r.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    /** A range like Roniki: named "Gel baby Pink", its variants "Gel Pink – …", mostly 350, one 600, one with its own text. */
    private String roniki(String w) throws Exception {
        String body = "{\"name\":\"Gel baby Pink " + w + "\",\"categoryIds\":[],\"published\":true,"
                + "\"description\":\"A soak-off gel polish.\",\"variants\":["
                + "{\"variantName\":\"Gel baby Pink " + w + "\",\"sellingPrice\":\"350\",\"isActive\":true},"
                + "{\"variantName\":\"Gel Pink " + w + " – Bubblegum Pink RE106\",\"sellingPrice\":\"350\",\"isActive\":true,"
                + "\"description\":\"A sweet bubblegum pink.\"},"
                + "{\"variantName\":\"Gel Pink " + w + " – Lilac Purple PU19\",\"sellingPrice\":\"350\",\"isActive\":true},"
                + "{\"variantName\":\"Gel Pink " + w + " – Glitter Gold DH 03\",\"sellingPrice\":\"600\",\"isActive\":true}]}";
        return ok(post("/api/v1/products/draft"), body).get("id").asText();
    }

    private Map<String, JsonNode> variantsByPart(String id) throws Exception {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode v : ok(get("/api/v1/products/" + id + "/range"), null).get("variants")) {
            out.put(v.get("namePart").isNull() ? "" : v.get("namePart").asText(), v);
        }
        return out;
    }

    private static String w() {
        return "zr" + UUID.randomUUID().toString().replaceAll("[^a-f]", "").substring(0, 6);
    }

    private String input(String name, String price, boolean priceVaries, boolean descriptionVaries, String extra) {
        return "{\"name\":\"" + name + "\",\"description\":\"A soak-off gel polish.\",\"sharedPrice\":" + price
                + ",\"sharedSize\":\"15ml\",\"categoryIds\":[],\"priceVaries\":" + priceVaries
                + ",\"sizeVaries\":false,\"descriptionVaries\":" + descriptionVaries + ",\"categoryVaries\":false"
                + (extra == null ? "" : "," + extra) + "}";
    }

    @Test
    void beforeItIsConfirmedTheConfigurationIsOnlySuggestedAndNothingChanges() throws Exception {
        String w = w();
        String id = roniki(w);
        JsonNode range = ok(get("/api/v1/products/" + id + "/range"), null);
        assertThat(range.get("reviewed").asBoolean()).isFalse();
        assertThat(range.get("suggested").asBoolean()).isTrue();
        JsonNode c = range.get("config");
        assertThat(c.get("name").asText()).as("the start most variant names share").isEqualTo("Gel Pink " + w);
        assertThat(c.get("sharedPrice").decimalValue()).isEqualByComparingTo("350");
        assertThat(c.get("priceVaries").asBoolean()).isTrue();
        assertThat(c.get("descriptionVaries").asBoolean()).isTrue();
        assertThat(variantsByPart(id).keySet())
                .containsExactly("Gel baby Pink " + w, "Bubblegum Pink RE106", "Lilac Purple PU19", "Glitter Gold DH 03");
        assertThat(range.get("notes").toString()).contains("Shared price KSh 350 (3 variants); 1 keep their own");

        JsonNode product = ok(get("/api/v1/products/" + id), null);
        assertThat(product.get("name").asText()).isEqualTo("Gel baby Pink " + w);
    }

    @Test
    void confirmingForcesTheCommonNameAndRenamingItKeepsEachVariantsOwnPart() throws Exception {
        String w = w();
        String id = roniki(w);
        String baseId = variantsByPart(id).get("Gel baby Pink " + w).get("id").asText();
        ok(put("/api/v1/products/" + id + "/range"), input("Roniki Gel " + w, "350", true, true,
                "\"nameParts\":{\"" + baseId + "\":\"Baby Pink\"}"));
        List<String> names = new ArrayList<>();
        ok(get("/api/v1/products/" + id), null).get("variants").forEach(v -> names.add(v.get("variantName").asText()));
        assertThat(names).containsExactly("Roniki Gel " + w + " – Baby Pink", "Roniki Gel " + w + " – Bubblegum Pink RE106",
                "Roniki Gel " + w + " – Lilac Purple PU19", "Roniki Gel " + w + " – Glitter Gold DH 03");

        ok(put("/api/v1/products/" + id + "/range"), input("Roniki Gel Polish " + w, "350", true, true, null));
        JsonNode product = ok(get("/api/v1/products/" + id), null);
        assertThat(product.get("name").asText()).isEqualTo("Roniki Gel Polish " + w);
        assertThat(product.get("variants").get(1).get("variantName").asText())
                .isEqualTo("Roniki Gel Polish " + w + " – Bubblegum Pink RE106");
    }

    @Test
    void aNewSharedPriceMovesTheFollowersAndAsksAboutTheOthers() throws Exception {
        String w = w();
        String id = roniki(w);
        ok(put("/api/v1/products/" + id + "/range"), input("Roniki Gel " + w, "350", true, true, null));
        JsonNode preview = ok(post("/api/v1/products/" + id + "/range/preview"),
                input("Roniki Gel " + w, "400", true, true, null));
        assertThat(preview.get("priceFollow").asInt()).isEqualTo(3);
        assertThat(preview.get("price")).singleElement().satisfies(o -> {
            assertThat(o.get("name").asText()).endsWith("Glitter Gold DH 03");
            assertThat(o.get("value").asText()).isEqualTo("KSh 600");
        });
        // Not ticked: the 600 one keeps its own price.
        ok(put("/api/v1/products/" + id + "/range"), input("Roniki Gel " + w, "400", true, true, null));
        Map<String, JsonNode> v = variantsByPart(id);
        assertThat(v.get("Lilac Purple PU19").get("price").decimalValue()).isEqualByComparingTo("400");
        assertThat(v.get("Glitter Gold DH 03").get("price").decimalValue()).isEqualByComparingTo("600");
        // Ticked: brought in line.
        String gold = v.get("Glitter Gold DH 03").get("id").asText();
        ok(put("/api/v1/products/" + id + "/range"), input("Roniki Gel " + w, "450", true, true,
                "\"bringInLine\":{\"price\":[\"" + gold + "\"]}"));
        assertThat(variantsByPart(id).values()).allSatisfy(x -> assertThat(x.get("price").decimalValue()).isEqualByComparingTo("450"));
    }

    @Test
    void whatTheRangeSharesWinsOverAVariantPageAndTheOwnNamePartIsKept() throws Exception {
        String w = w();
        String id = roniki(w);
        ok(put("/api/v1/products/" + id + "/range"), input("Roniki Gel " + w, "350", false, false, null));
        Map<String, JsonNode> v = variantsByPart(id);
        assertThat(v.values()).allSatisfy(x -> {
            assertThat(x.get("price").decimalValue()).isEqualByComparingTo("350");
            assertThat(x.get("ownDescription").asBoolean()).as("descriptions are shared").isFalse();
        });
        String lilac = v.get("Lilac Purple PU19").get("id").asText();
        ok(put("/api/v1/products/" + id + "/variants/" + lilac),
                "{\"variantName\":\"Roniki Gel " + w + " – Lilac PU19\",\"description\":\"Own text\",\"sellingPrice\":\"999\","
                        + "\"isActive\":true}");
        JsonNode after = variantsByPart(id).get("Lilac PU19");
        assertThat(after).as("the own part edited on the variant's page is kept").isNotNull();
        assertThat(after.get("name").asText()).isEqualTo("Roniki Gel " + w + " – Lilac PU19");
        assertThat(after.get("price").decimalValue()).as("the shared price wins").isEqualByComparingTo("350");
        assertThat(after.get("ownDescription").asBoolean()).isFalse();
    }

    @Test
    void aVariantFiledUnderItsOwnCategoryIsListedThereInTheShop() throws Exception {
        String w = w();
        String id = roniki(w);
        Category nails = categories.findAll().stream().filter(c -> c.getParent() == null).findFirst().orElseThrow();
        Category other = categories.findAll().stream().filter(c -> c.getParent() == null && !c.getId().equals(nails.getId()))
                .findFirst().orElseThrow();
        ok(put("/api/v1/products/" + id + "/range"), input("Roniki Gel " + w, "350", true, true, null)
                .replace("\"categoryIds\":[]", "\"categoryIds\":[\"" + nails.getId() + "\"]")
                .replace("\"categoryVaries\":false", "\"categoryVaries\":true"));
        String gold = variantsByPart(id).get("Glitter Gold DH 03").get("id").asText();
        // File one variant under the other category (as a later variant page will).
        var p = products.findById(UUID.fromString(id)).orElseThrow();
        assertThat(p.isCategoryVaries()).isTrue();
        variantCategory(gold, other.getId());

        List<String> inOther = shop(other.getSlug(), w);
        List<String> inNails = shop(nails.getSlug(), w);
        assertThat(inOther).containsExactly(gold);
        assertThat(inNails).hasSize(3).doesNotContain(gold);
    }

    @Autowired private com.nbh.backend.catalog.repository.ProductVariantRepository variantRepo;

    private void variantCategory(String variantId, UUID categoryId) {
        var v = variantRepo.findById(UUID.fromString(variantId)).orElseThrow();
        v.setCategory(categories.findById(categoryId).orElseThrow());
        variantRepo.saveAndFlush(v);
    }

    private List<String> shop(String categorySlug, String w) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/catalog/variants").param("category", categorySlug).param("q", w)
                .param("size", "50")).andReturn();
        List<String> ids = new ArrayList<>();
        json.readTree(r.getResponse().getContentAsString()).get("content").forEach(c -> ids.add(c.get("variantId").asText()));
        return ids;
    }
}
