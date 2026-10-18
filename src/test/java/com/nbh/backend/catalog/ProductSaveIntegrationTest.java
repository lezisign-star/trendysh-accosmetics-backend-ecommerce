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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

    /**
     * Regression test for a live bug (2026-09-29): publishing used to go through the normal
     * managed-entity save, which bumped {@code updatedAt} same as any editor save would. A staff
     * member who published a fresh draft and then saved the same open editor shortly after could
     * get a spurious 409 whenever the two requests' commits happened to land in different
     * wall-clock seconds — a real gap of well under a second, but enough to trip the
     * truncated-to-seconds comparison in the conflict check above. That only reproduced
     * intermittently (it depends on where a second boundary happens to fall), so this asserts the
     * underlying invariant directly instead of racing the clock: publishing must leave
     * updatedAt untouched, full stop, not "usually unchanged within the same second."
     */
    @Test
    void publishingDoesNotChangeUpdatedAtSoAFollowUpSaveWithTheOriginalTimestampSucceeds() throws Exception {
        JsonNode draft = ok(post("/api/v1/products/draft"), "{\"name\":\"" + unique("Publish Then Save")
                + "\",\"categoryIds\":[],\"published\":false,\"variants\":[]}");
        String id = draft.get("id").asText();
        String draftUpdatedAt = draft.get("updatedAt").asText();

        JsonNode published = ok(post("/api/v1/products/" + id + "/publish"), null);
        assertThat(published.get("updatedAt").asText()).isEqualTo(draftUpdatedAt);

        // A save using the pre-publish snapshot must therefore still be accepted, not rejected
        // as a conflict just because publishing happened in between.
        JsonNode saved = ok(put("/api/v1/products/" + id + "/full"), "{"
                + "\"expectedUpdatedAt\":\"" + draftUpdatedAt + "\","
                + "\"name\":\"" + draft.get("name").asText() + "\",\"categoryIds\":[],"
                + "\"published\":true,\"variants\":[]}");
        assertThat(saved.get("isPublished").asBoolean()).isTrue();
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
    void aNewVariantsSkuIsAutoGeneratedFromItsCategory() throws Exception {
        String categoryId = newCategory(unique("Lashes"), null);
        JsonNode draft = ok(post("/api/v1/products/draft"), "{"
                + "\"name\":\"" + unique("Mink lashes") + "\","
                + "\"categoryIds\":[\"" + categoryId + "\"],"
                + "\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"isActive\":true}]}");

        String sku = draft.get("variants").get(0).get("sku").asText();
        assertThat(sku).matches("LASH-\\d{6}");
    }

    @Test
    void twoAutoGeneratedSkusInARowAreDifferent() throws Exception {
        String body = "{\"name\":\"" + unique("Plain product") + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"isActive\":true}]}";

        String sku1 = ok(post("/api/v1/products/draft"), body).get("variants").get(0).get("sku").asText();
        String sku2 = ok(post("/api/v1/products/draft"), body).get("variants").get(0).get("sku").asText();

        assertThat(sku1).isNotEqualTo(sku2);
        assertThat(sku1).matches("SKU-\\d{6}"); // no category on this one -> generic prefix
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

    // ---------------------------------------------------------------- the name autosave's own endpoint

    private JsonNode hiddenProduct(String name) throws Exception {
        return ok(post("/api/v1/products/draft"), "{\"name\":\"" + name + "\","
                + "\"description\":\"A soft matte lipstick.\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}");
    }

    @Test
    void aRenameChangesTheNameAndNothingElse() throws Exception {
        JsonNode before = hiddenProduct(unique("Plum Lipstick"));
        String id = before.get("id").asText();
        String newName = unique("Plum Satin Lipstick");

        JsonNode after = ok(patch("/api/v1/products/" + id + "/name"),
                "{\"expectedUpdatedAt\":\"" + before.get("updatedAt").asText() + "\",\"name\":\"  " + newName + " \"}");

        assertThat(after.get("name").asText()).as("tidied, and saved").isEqualTo(newName);
        assertThat(after.get("description").asText()).isEqualTo("A soft matte lipstick.");
        assertThat(after.get("isPublished").asBoolean()).isFalse();
        assertThat(after.get("variants")).hasSize(1);
        assertThat(after.get("variants").get(0).get("id").asText()).isEqualTo(before.get("variants").get(0).get("id").asText());
        assertThat(after.get("variants").get(0).get("sellingPrice").asDouble()).isEqualTo(1000.0);
        assertThat(ok(get("/api/v1/products/" + id), null).get("name").asText()).isEqualTo(newName);
    }

    @Test
    void aRenameCannotTouchTheShopSwitchWhateverTheRequestSays() throws Exception {
        // An editor left open since before the product was hidden still believes it is "in the shop": the old
        // autosave sent that belief back with the name and put the product on sale again. The endpoint reads only
        // the name, so even a request that carries the switch cannot move it.
        JsonNode before = hiddenProduct(unique("Hidden Gloss"));
        String id = before.get("id").asText();

        ok(patch("/api/v1/products/" + id + "/name"),
                "{\"expectedUpdatedAt\":\"" + before.get("updatedAt").asText() + "\","
                        + "\"name\":\"" + unique("Hidden Gloss Two") + "\",\"published\":true}");

        assertThat(ok(get("/api/v1/products/" + id), null).get("isPublished").asBoolean()).isFalse();
    }

    @Test
    void aProductThatIsInTheShopIsNotRenamedBehindStaffsBack() throws Exception {
        JsonNode before = hiddenProduct(unique("Now Live Balm"));
        String id = before.get("id").asText();
        ok(post("/api/v1/products/" + id + "/publish"), null); // published from the list while an editor was open

        int code = status(patch("/api/v1/products/" + id + "/name"),
                "{\"expectedUpdatedAt\":\"" + before.get("updatedAt").asText() + "\",\"name\":\"Renamed while live\"}");

        assertThat(code).isEqualTo(409);
        JsonNode after = ok(get("/api/v1/products/" + id), null);
        assertThat(after.get("name").asText()).isEqualTo(before.get("name").asText());
        assertThat(after.get("isPublished").asBoolean()).isTrue();
    }

    @Test
    void aRenameFromAnOlderFormIsRefusedAndABlankNameIsNotSaved() throws Exception {
        JsonNode before = hiddenProduct(unique("Old Form Cream"));
        String id = before.get("id").asText();

        // Someone saved the product after this form was loaded.
        assertThat(status(patch("/api/v1/products/" + id + "/name"),
                "{\"expectedUpdatedAt\":\"2020-01-01T00:00:00Z\",\"name\":\"Too late\"}")).isEqualTo(409);
        // A name must have letters in it.
        assertThat(status(patch("/api/v1/products/" + id + "/name"),
                "{\"expectedUpdatedAt\":\"" + before.get("updatedAt").asText() + "\",\"name\":\"   \"}")).isEqualTo(422);
        // And no product, no rename.
        assertThat(status(patch("/api/v1/products/" + UUID.randomUUID() + "/name"), "{\"name\":\"Nobody\"}")).isEqualTo(404);

        assertThat(ok(get("/api/v1/products/" + id), null).get("name").asText()).isEqualTo(before.get("name").asText());
    }

    @Test
    void renamingNeedsTheRightToManageProducts() throws Exception {
        JsonNode before = hiddenProduct(unique("Staff Only Mask"));
        int signedOut = mvc.perform(patch("/api/v1/products/" + before.get("id").asText() + "/name")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Anyone\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(signedOut).isIn(401, 403);
    }
}
