package com.nbh.backend.packages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.repository.CategoryRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The Professions screen (owner, 2026-10-24): a trade can be renamed (its link stays), described and given a picture,
 * deleted only while no package is under it, and a package can be listed under other trades besides its main one, where the shop
 * shows it too. Same Spring context as the other package tests (identical annotations).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfessionsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CategoryRepository categoryRepository;

    private String token;

    @BeforeEach
    void setUp() {
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
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isIn(200, 204);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private JsonNode newProfession(String label) throws Exception {
        return ok(post("/api/v1/admin/professions"),
                "{\"label\":\"" + label + "\",\"slug\":\"trade-" + UUID.randomUUID().toString().substring(0, 8) + "\"}");
    }

    private String newCategory() {
        Category c = new Category();
        c.setSlug("trade-category-" + UUID.randomUUID().toString().substring(0, 8));
        c.setName(unique("Trade Category"));
        return categoryRepository.save(c).getId().toString();
    }

    private String newVariant() throws Exception {
        JsonNode product = ok(post("/api/v1/products/draft"), "{\"name\":\"" + unique("Trade Product")
                + "\",\"categoryIds\":[],\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"isActive\":true}]}");
        ok(post("/api/v1/products/" + product.get("id").asText() + "/publish"), null);
        return product.get("variants").get(0).get("id").asText();
    }

    private String packageBody(String name, String professionId, List<String> alsoFor, String variantId, String category) {
        String also = alsoFor == null ? "null" : "[" + String.join(",", alsoFor.stream().map(id -> "\"" + id + "\"").toList()) + "]";
        return "{\"name\":\"" + name + "\",\"professionId\":\"" + professionId + "\",\"categoryId\":\"" + category
                + "\",\"description\":\"For a technician\",\"finalPrice\":\"450\",\"components\":[{\"variantId\":\""
                + variantId + "\",\"qty\":\"1\"}],\"alsoForProfessionIds\":" + also + "}";
    }

    private JsonNode professionById(String id) throws Exception {
        for (JsonNode p : ok(get("/api/v1/admin/professions"), null)) {
            if (p.get("id").asText().equals(id)) return p;
        }
        throw new AssertionError("profession not listed: " + id);
    }

    @Test
    void aTradeIsRenamedDescribedAndGivenAPictureAndItsLinkStays() throws Exception {
        JsonNode trade = newProfession(unique("Nail Tech"));
        String slug = trade.get("slug").asText();
        String label = unique("Nail Technician");

        JsonNode updated = ok(put("/api/v1/admin/professions/" + trade.get("id").asText()), "{\"label\":\"" + label
                + "\",\"description\":\"Gel, acrylic and nail art\",\"imageUrl\":\"https://res.cloudinary.com/demo/trade.jpg\"}");
        assertThat(updated.get("label").asText()).isEqualTo(label);
        assertThat(updated.get("slug").asText()).as("shared links keep working").isEqualTo(slug);
        assertThat(updated.get("description").asText()).isEqualTo("Gel, acrylic and nail art");
        assertThat(updated.get("imageUrl").asText()).isEqualTo("https://res.cloudinary.com/demo/trade.jpg");

        // a blank description clears it; another trade's name is refused
        JsonNode cleared = ok(put("/api/v1/admin/professions/" + trade.get("id").asText()),
                "{\"label\":\"" + label + "\",\"description\":\"  \",\"imageUrl\":null}");
        assertThat(cleared.get("description").isNull()).isTrue();
        JsonNode other = newProfession(unique("Lash Tech"));
        MvcResult clash = call(put("/api/v1/admin/professions/" + other.get("id").asText()), "{\"label\":\"" + label + "\"}");
        assertThat(clash.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void aPackageListedUnderTwoTradesShowsUnderBothCountsInBothAndKeepsBothFromBeingDeleted() throws Exception {
        JsonNode main = newProfession(unique("Nail Tech"));
        JsonNode extra = newProfession(unique("Pedicurist"));
        ok(put("/api/v1/admin/professions/" + extra.get("id").asText()), "{\"label\":\"" + extra.get("label").asText()
                + "\",\"description\":\"Feet and spa\",\"imageUrl\":\"https://res.cloudinary.com/demo/pedi.jpg\"}");
        String mainId = main.get("id").asText(), extraId = extra.get("id").asText();

        JsonNode pkg = ok(post("/api/v1/admin/packages"), packageBody(unique("Two Trade Package"), mainId, List.of(extraId, mainId),
                newVariant(), newCategory()));
        assertThat(pkg.get("alsoForProfessionIds")).as("the main trade is not repeated").hasSize(1);
        assertThat(pkg.get("alsoForProfessionIds").get(0).asText()).isEqualTo(extraId);

        assertThat(professionById(mainId).get("packageCount").asLong()).isEqualTo(1);
        assertThat(professionById(extraId).get("packageCount").asLong()).isEqualTo(1);

        // the admin list finds it under either trade
        JsonNode byExtra = ok(get("/api/v1/admin/packages").param("professionId", extraId), null);
        assertThat(byExtra.get("totalElements").asLong()).isEqualTo(1);

        // the shop lists it with both trades, the main one first, each with its picture and description
        JsonNode shop = json.readTree(mvc.perform(get("/api/v1/catalog/packages")).andReturn().getResponse().getContentAsString());
        JsonNode listed = null;
        for (JsonNode k : shop) if (k.get("variantId").asText().equals(pkg.get("variantId").asText())) listed = k;
        assertThat(listed).isNotNull();
        List<String> trades = new ArrayList<>();
        listed.get("professions").forEach(t -> trades.add(t.get("id").asText()));
        assertThat(trades).containsExactly(mainId, extraId);
        assertThat(listed.get("professions").get(1).get("description").asText()).isEqualTo("Feet and spa");
        assertThat(listed.get("professions").get(1).get("imageUrl").asText()).isEqualTo("https://res.cloudinary.com/demo/pedi.jpg");

        // neither trade can be deleted while the package is under it
        MvcResult refused = call(delete("/api/v1/admin/professions/" + extraId), null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("1 package");

        // taken off the extra trade, that trade is free again and can go
        ok(put("/api/v1/admin/packages/" + pkg.get("id").asText()), packageBody(pkg.get("name").asText(), mainId, List.of(),
                pkg.get("components").get(0).get("variantId").asText(), pkg.get("categoryId").asText()));
        assertThat(professionById(extraId).get("packageCount").asLong()).isZero();
        ok(delete("/api/v1/admin/professions/" + extraId), null);
        assertThat(call(delete("/api/v1/admin/professions/" + mainId), null).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void anUnusedTradeIsDeletedAndAMissingOneIsNotFound() throws Exception {
        JsonNode trade = newProfession(unique("Brow Artist"));
        ok(delete("/api/v1/admin/professions/" + trade.get("id").asText()), null);
        assertThat(call(delete("/api/v1/admin/professions/" + trade.get("id").asText()), null).getResponse().getStatus())
                .isEqualTo(404);
    }
}
