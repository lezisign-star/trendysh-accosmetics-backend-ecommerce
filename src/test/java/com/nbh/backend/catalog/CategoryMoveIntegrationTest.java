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
 * Moving products and categories (owner, 2026-10-25): the product list's "Move to another category" bulk action, "move
 * everything out of a category" (merging two, or emptying one so it can go), and a subcategory under a different category.
 * Unique names per run so it can share the database with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CategoryMoveIntegrationTest {

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

    /** The status and the message of a request that is expected to be refused. */
    private String refused(MockHttpServletRequestBuilder request, String body, int status) throws Exception {
        MvcResult result = call(request, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return result.getResponse().getContentAsString();
    }

    private String newCategory(String name, String parentId) throws Exception {
        String body = "{\"name\":\"" + name + "\"" + (parentId != null ? ",\"parentId\":\"" + parentId + "\"" : "") + "}";
        return ok(post("/api/v1/categories"), body).get("id").asText();
    }

    private String newProduct(String name, String... categoryIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (String id : categoryIds) ids.append(ids.length() == 0 ? "" : ",").append('"').append(id).append('"');
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[" + ids + "],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Only\",\"sellingPrice\":\"100\",\"isActive\":true}]}";
        return ok(post("/api/v1/products/draft"), body).get("id").asText();
    }

    private List<String> categoriesOf(String productId) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode c : ok(get("/api/v1/products/" + productId), null).get("categories")) ids.add(c.get("id").asText());
        return ids;
    }

    private JsonNode category(String id) throws Exception {
        for (JsonNode c : ok(get("/api/v1/categories"), null)) {
            if (id.equals(c.get("id").asText())) return c;
        }
        return null;
    }

    private String bulkMove(String from, String to, String... productIds) {
        StringBuilder ids = new StringBuilder();
        for (String id : productIds) ids.append(ids.length() == 0 ? "" : ",").append('"').append(id).append('"');
        return "{\"action\":\"MOVE_CATEGORY\",\"productIds\":[" + ids + "],\"fromCategoryId\":\"" + from
                + "\",\"toCategoryId\":\"" + to + "\"}";
    }

    @Test
    void tickedProductsLeaveOneCategoryForAnotherAndKeepTheirOtherCategoriesAndTheirPrimarySlot() throws Exception {
        String nails = newCategory(unique("Nails"), null);
        String gel = newCategory(unique("Gel polish"), nails);
        String brushes = newCategory(unique("Brushes"), nails);
        String lashes = newCategory(unique("Lashes"), null);

        String inGelAndLashes = newProduct(unique("Move A"), gel, lashes);
        String inGelOnly = newProduct(unique("Move B"), gel);
        String inBrushesOnly = newProduct(unique("Move C"), brushes);
        String inGelAndBrushes = newProduct(unique("Move D"), gel, brushes);

        JsonNode result = ok(post("/api/v1/products/bulk"),
                bulkMove(gel, brushes, inGelAndLashes, inGelOnly, inBrushesOnly, inGelAndBrushes));

        // three left the gel category (the fourth was never in it)
        assertThat(result.get("affectedProducts").asInt()).isEqualTo(3);
        assertThat(result.get("warnings").toString()).contains("not in the category you moved from");
        assertThat(result.get("warnings").toString()).contains("already in the category you moved to");

        // the primary slot is kept, the other category stays
        assertThat(categoriesOf(inGelAndLashes)).containsExactly(brushes, lashes);
        assertThat(categoriesOf(inGelOnly)).containsExactly(brushes);
        assertThat(categoriesOf(inBrushesOnly)).containsExactly(brushes);
        // already under the destination: only the old one is taken off, nothing twice
        assertThat(categoriesOf(inGelAndBrushes)).containsExactly(brushes);
    }

    @Test
    void movingFromACategoryToItselfOrToNowhereIsRefused() throws Exception {
        String a = newCategory(unique("Same"), null);
        String product = newProduct(unique("Move same"), a);
        assertThat(refused(post("/api/v1/products/bulk"), bulkMove(a, a, product), 400)).contains("same category");
        assertThat(refused(post("/api/v1/products/bulk"),
                "{\"action\":\"MOVE_CATEGORY\",\"productIds\":[\"" + product + "\"],\"fromCategoryId\":\"" + a + "\"}", 400))
                .contains("Choose the category");
        assertThat(categoriesOf(product)).containsExactly(a);
    }

    @Test
    void everythingInACategoryMovesIncludingDeletedProductsSoTheCategoryCanThenBeDeleted() throws Exception {
        String from = newCategory(unique("Old shelf"), null);
        String to = newCategory(unique("New shelf"), null);
        String live1 = newProduct(unique("Shelf live 1"), from);
        String live2 = newProduct(unique("Shelf live 2"), from, to);
        String trashed = newProduct(unique("Shelf trashed"), from);
        ok(post("/api/v1/products/bulk"), "{\"action\":\"DELETE\",\"productIds\":[\"" + trashed + "\"]}");

        // the screen's own count is the live ones; deleting is blocked by all three
        assertThat(category(from).get("directProductCount").asInt()).isEqualTo(2);
        assertThat(refused(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/categories/" + from),
                null, 409)).contains("still has 3 products");

        JsonNode result = ok(post("/api/v1/categories/" + from + "/move-products"), "{\"toCategoryId\":\"" + to + "\"}");
        assertThat(result.get("moved").asInt()).isEqualTo(3);
        assertThat(result.get("alreadyThere").asInt()).isEqualTo(1);
        assertThat(result.get("deleted").asBoolean()).isFalse();

        assertThat(categoriesOf(live1)).containsExactly(to);
        assertThat(categoriesOf(live2)).containsExactly(to);
        assertThat(categoriesOf(trashed)).containsExactly(to);
        assertThat(category(from).get("directProductCount").asInt()).isZero();
        assertThat(category(to).get("directProductCount").asInt()).isEqualTo(2);

        // nothing blocks it any more
        ok(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/categories/" + from), null);
        assertThat(category(from)).isNull();
    }

    @Test
    void deleteWhenEmptyMergesTwoCategoriesButKeepsOneThatStillHasSubcategories() throws Exception {
        String keep = newCategory(unique("Keep"), null);

        // a leaf category is deleted once its products are gone
        String leaf = newCategory(unique("Leaf"), null);
        newProduct(unique("Leaf product"), leaf);
        JsonNode merged = ok(post("/api/v1/categories/" + leaf + "/move-products"),
                "{\"toCategoryId\":\"" + keep + "\",\"deleteWhenEmpty\":true}");
        assertThat(merged.get("moved").asInt()).isEqualTo(1);
        assertThat(merged.get("deleted").asBoolean()).isTrue();
        assertThat(category(leaf)).isNull();

        // one with a subcategory is kept, and the subcategory's own products are not touched
        String parent = newCategory(unique("Parent"), null);
        String sub = newCategory(unique("Sub"), parent);
        String inParent = newProduct(unique("Parent product"), parent);
        String inSub = newProduct(unique("Sub product"), sub);
        JsonNode kept = ok(post("/api/v1/categories/" + parent + "/move-products"),
                "{\"toCategoryId\":\"" + keep + "\",\"deleteWhenEmpty\":true}");
        assertThat(kept.get("moved").asInt()).isEqualTo(1);
        assertThat(kept.get("deleted").asBoolean()).isFalse();
        assertThat(kept.get("note").asText()).contains("still has 1 subcategory");
        assertThat(categoriesOf(inParent)).containsExactly(keep);
        assertThat(categoriesOf(inSub)).containsExactly(sub);
        assertThat(category(parent)).isNotNull();
    }

    @Test
    void aSubcategoryGoesUnderAnotherCategoryWithItsProductsAndTheCountsFollow() throws Exception {
        String a = newCategory(unique("Category A"), null);
        String b = newCategory(unique("Category B"), null);
        String sub = newCategory(unique("Wandering sub"), a);
        String product = newProduct(unique("Wandering product"), sub);
        assertThat(category(a).get("productCount").asInt()).isEqualTo(1);
        assertThat(category(b).get("productCount").asInt()).isZero();

        JsonNode moved = ok(post("/api/v1/categories/" + sub + "/move"), "{\"parentId\":\"" + b + "\"}");
        assertThat(moved.get("parentId").asText()).isEqualTo(b);

        assertThat(categoriesOf(product)).containsExactly(sub);
        assertThat(category(a).get("productCount").asInt()).isZero();
        assertThat(category(b).get("productCount").asInt()).isEqualTo(1);

        // and back to the top level
        JsonNode top = ok(post("/api/v1/categories/" + sub + "/move"), "{\"parentId\":null}");
        assertThat(top.get("parentId").isNull()).isTrue();
        assertThat(category(b).get("productCount").asInt()).isZero();
    }

    @Test
    void theTwoLevelRuleAndNamesBesideEachOtherStillHoldWhenMovingACategory() throws Exception {
        String a = newCategory(unique("Rule A"), null);
        String b = newCategory(unique("Rule B"), null);
        String subOfA = newCategory(unique("Rule sub"), a);

        // a category with subcategories cannot go under another one
        assertThat(refused(post("/api/v1/categories/" + a + "/move"), "{\"parentId\":\"" + b + "\"}", 409))
                .contains("can't go under another category");
        // nor under a subcategory, nor under itself
        assertThat(refused(post("/api/v1/categories/" + b + "/move"), "{\"parentId\":\"" + subOfA + "\"}", 400))
                .contains("top-level");
        assertThat(refused(post("/api/v1/categories/" + b + "/move"), "{\"parentId\":\"" + b + "\"}", 400))
                .contains("under itself");

        // the same name beside it is refused, under a parent and at the top level
        String name = unique("Same name");
        newCategory(name, b);
        String twin = newCategory(name, a);
        assertThat(refused(post("/api/v1/categories/" + twin + "/move"), "{\"parentId\":\"" + b + "\"}", 409))
                .contains("already a category called");
        String topTwin = newCategory(name, null);
        assertThat(refused(post("/api/v1/categories/" + twin + "/move"), "{\"parentId\":null}", 409))
                .contains("already a category called");
        assertThat(category(topTwin)).isNotNull();

        // a nothing-to-do move is fine
        ok(post("/api/v1/categories/" + subOfA + "/move"), "{\"parentId\":\"" + a + "\"}");
    }

    @Test
    void movingIsForStaffWhoManageProductsOnly() throws Exception {
        String a = newCategory(unique("Locked A"), null);
        String b = newCategory(unique("Locked B"), null);
        // no sign-in at all
        assertThat(mvc.perform(post("/api/v1/categories/" + a + "/move-products").contentType(MediaType.APPLICATION_JSON)
                .content("{\"toCategoryId\":\"" + b + "\"}")).andReturn().getResponse().getStatus()).isIn(401, 403);
        assertThat(mvc.perform(post("/api/v1/categories/" + a + "/move").contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":\"" + b + "\"}")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }
}
