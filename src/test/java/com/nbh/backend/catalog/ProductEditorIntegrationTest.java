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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The product editor's API, through the real security chain and database: a two-level category tree
 * with consistency rules, staff filters, setting stock through the ledger, and soft delete / restore.
 * Unique names per run so it can share the database with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductEditorIntegrationTest {

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

    private JsonNode newProduct(String name, String categoryId, String subcategoryId) throws Exception {
        String body = "{\"name\":\"" + name + "\""
                + (categoryId != null ? ",\"categoryId\":\"" + categoryId + "\"" : "")
                + (subcategoryId != null ? ",\"subcategoryId\":\"" + subcategoryId + "\"" : "")
                + ",\"variants\":[{\"sku\":\"SKU-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"vatExempt\":false}]}";
        return ok(post("/api/v1/products"), body);
    }

    @Test
    void oneCategoryHoldsManySubcategoriesAndManyProducts() throws Exception {
        String nails = newCategory(unique("Nails"), null);
        String gel = newCategory(unique("Gel"), nails);
        String acrylic = newCategory(unique("Acrylic"), nails);

        JsonNode p1 = newProduct(unique("Gel polish"), nails, gel);
        JsonNode p2 = newProduct(unique("Gel remover"), nails, gel);
        JsonNode p3 = newProduct(unique("Acrylic powder"), nails, acrylic);

        assertThat(p1.get("subcategoryId").asText()).isEqualTo(gel);
        assertThat(p1.get("categoryId").asText()).isEqualTo(nails);

        // The tree reports counts: the parent counts every product below it.
        JsonNode tree = ok(get("/api/v1/categories"), null);
        long parentCount = 0, gelCount = 0, acrylicCount = 0;
        for (JsonNode c : tree) {
            if (c.get("id").asText().equals(nails)) parentCount = c.get("productCount").asLong();
            if (c.get("id").asText().equals(gel)) gelCount = c.get("productCount").asLong();
            if (c.get("id").asText().equals(acrylic)) acrylicCount = c.get("productCount").asLong();
        }
        assertThat(parentCount).isEqualTo(3);
        assertThat(gelCount).isEqualTo(2);
        assertThat(acrylicCount).isEqualTo(1);
        assertThat(p2.get("id").asText()).isNotEqualTo(p3.get("id").asText());

        // Filtering by the subcategory returns only its products; by the parent returns all three.
        assertThat(ok(get("/api/v1/products?categoryId=" + gel), null).get("totalElements").asInt()).isEqualTo(2);
        assertThat(ok(get("/api/v1/products?categoryId=" + nails), null).get("totalElements").asInt()).isEqualTo(3);
    }

    @Test
    void aSubcategoryMustBelongToTheChosenCategory() throws Exception {
        String nails = newCategory(unique("Nails"), null);
        String lashes = newCategory(unique("Lashes"), null);
        String gel = newCategory(unique("Gel"), nails);

        assertThat(status(post("/api/v1/products"), "{\"name\":\"Wrong pair\",\"categoryId\":\"" + lashes
                + "\",\"subcategoryId\":\"" + gel + "\",\"variants\":[{\"sku\":\"X-" + UUID.randomUUID()
                + "\",\"variantName\":\"D\",\"sellingPrice\":\"1\",\"vatExempt\":false}]}")).isEqualTo(400);
        // A top-level category can't be used as a subcategory, nor a subcategory as the category.
        assertThat(status(post("/api/v1/products"), "{\"name\":\"Top as sub\",\"categoryId\":\"" + nails
                + "\",\"subcategoryId\":\"" + lashes + "\",\"variants\":[{\"sku\":\"Y-" + UUID.randomUUID()
                + "\",\"variantName\":\"D\",\"sellingPrice\":\"1\",\"vatExempt\":false}]}")).isEqualTo(400);
        // Choosing only the subcategory files the product under its parent.
        JsonNode p = newProduct(unique("Only sub"), null, gel);
        assertThat(p.get("categoryId").asText()).isEqualTo(nails);
    }

    @Test
    void subcategoriesCannotNestAndAnOccupiedCategoryCannotBeDeleted() throws Exception {
        String nails = newCategory(unique("Nails"), null);
        String gel = newCategory(unique("Gel"), nails);

        assertThat(status(post("/api/v1/categories"), "{\"name\":\"Too deep\",\"parentId\":\"" + gel + "\"}")).isEqualTo(400);

        newProduct(unique("Gel polish"), nails, gel);
        assertThat(status(delete("/api/v1/categories/" + nails), null)).isEqualTo(409); // has a subcategory
        assertThat(status(delete("/api/v1/categories/" + gel), null)).isEqualTo(409);   // has a product

        String empty = newCategory(unique("Empty"), null);
        assertThat(status(delete("/api/v1/categories/" + empty), null)).isEqualTo(200);
    }

    @Test
    void renamingKeepsSlugsUniqueAndUpdatesTheOrder() throws Exception {
        String name = unique("Spa");
        String a = newCategory(name, null);
        String b = newCategory(name, null); // same name twice must not collide on the slug
        assertThat(a).isNotEqualTo(b);

        JsonNode renamed = ok(patch("/api/v1/categories/" + b), "{\"name\":\"" + unique("Salon") + "\",\"sortOrder\":7}");
        assertThat(renamed.get("sortOrder").asInt()).isEqualTo(7);
        assertThat(renamed.get("name").asText()).startsWith("Salon");
    }

    @Test
    void settingStockPostsThroughTheLedgerAndDrivesTheStockFilter() throws Exception {
        String cat = newCategory(unique("Brows"), null);
        JsonNode product = newProduct(unique("Brow pencil"), cat, null);
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();

        // Freshly created: nothing sellable.
        assertThat(ok(get("/api/v1/products?categoryId=" + cat + "&stock=OUT"), null).get("totalElements").asInt()).isEqualTo(1);
        assertThat(ok(get("/api/v1/products?categoryId=" + cat + "&stock=IN"), null).get("totalElements").asInt()).isZero();

        JsonNode up = ok(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock"), "{\"quantity\":12}");
        assertThat(up.get("previousAvailable").asInt()).isZero();
        assertThat(up.get("newAvailable").asInt()).isEqualTo(12);
        assertThat(up.get("documentNo").asText()).isNotBlank();

        JsonNode listed = ok(get("/api/v1/products?categoryId=" + cat + "&stock=IN"), null);
        assertThat(listed.get("totalElements").asInt()).isEqualTo(1);
        assertThat(listed.get("content").get(0).get("variants").get(0).get("availableQty").asInt()).isEqualTo(12);

        // Correcting downwards writes it off; the same number again is a no-op.
        JsonNode down = ok(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock"), "{\"quantity\":5}");
        assertThat(down.get("newAvailable").asInt()).isEqualTo(5);
        JsonNode same = ok(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock"), "{\"quantity\":5}");
        assertThat(same.get("documentNo").isNull()).isTrue();

        assertThat(status(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock"), "{\"quantity\":-1}")).isEqualTo(400);
    }

    @Test
    void searchFindsBySkuAndPublishedFilterWorks() throws Exception {
        String cat = newCategory(unique("Lashes"), null);
        String name = unique("Silk lash");
        JsonNode product = newProduct(name, cat, null);
        String sku = product.get("variants").get(0).get("sku").asText();

        assertThat(ok(get("/api/v1/products?q=" + sku), null).get("totalElements").asInt()).isEqualTo(1);
        assertThat(ok(get("/api/v1/products?categoryId=" + cat + "&published=true"), null).get("totalElements").asInt()).isZero();
        assertThat(ok(get("/api/v1/products?categoryId=" + cat + "&published=false"), null).get("totalElements").asInt()).isEqualTo(1);
        assertThat(status(get("/api/v1/products?stock=MAYBE"), null)).isEqualTo(400);
    }

    @Test
    void deletingHidesAProductAndRestoringBringsItBackUnpublished() throws Exception {
        String cat = newCategory(unique("Tools"), null);
        JsonNode product = newProduct(unique("Cuticle nipper"), cat, null);
        String id = product.get("id").asText();
        ok(post("/api/v1/products/" + id + "/publish"), null);

        JsonNode deleted = ok(delete("/api/v1/products/" + id), null);
        assertThat(deleted.get("deleted").asBoolean()).isTrue();
        assertThat(deleted.get("isPublished").asBoolean()).isFalse();

        assertThat(ok(get("/api/v1/products?categoryId=" + cat), null).get("totalElements").asInt()).isZero();
        assertThat(ok(get("/api/v1/products?categoryId=" + cat + "&deleted=true"), null).get("totalElements").asInt()).isEqualTo(1);
        // Still counted as blocking the category until the product is moved or restored elsewhere.
        assertThat(status(delete("/api/v1/categories/" + cat), null)).isEqualTo(409);

        JsonNode restored = ok(post("/api/v1/products/" + id + "/restore"), null);
        assertThat(restored.get("deleted").asBoolean()).isFalse();
        assertThat(restored.get("isPublished").asBoolean()).isFalse();
        assertThat(ok(get("/api/v1/products?categoryId=" + cat), null).get("totalElements").asInt()).isEqualTo(1);
    }
}
