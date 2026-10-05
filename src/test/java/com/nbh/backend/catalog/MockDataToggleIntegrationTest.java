package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.catalog.service.MockDataSeederService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The developer-only demo-data toggle: enable generates a realistic catalogue tagged is_mock_data,
 * disable hides it (rows kept, not deleted), a second enable brings the SAME rows back without
 * regenerating, and only the DEVELOPER role — not SUPER_ADMIN, which holds the identical permission
 * set — can use any of it. See MockDataSeederService for the design.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MockDataToggleIntegrationTest {

    private static final List<String> TOP_LEVEL = List.of("nails", "lashes", "brows", "make-up", "spa", "salon-equipment");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private MockDataSeederService seeder;

    private String developerToken;
    private String superAdminToken;

    @BeforeEach
    void setUp() {
        for (String slug : TOP_LEVEL) {
            if (categories.findBySlug(slug).isEmpty()) {
                Category c = new Category();
                c.setSlug(slug);
                c.setName(slug);
                categories.save(c);
            }
        }
        developerToken = jwtService.generateAccessToken(staffWithRole("DEVELOPER"));
        superAdminToken = jwtService.generateAccessToken(staffWithRole("SUPER_ADMIN"));
    }

    /** The generated rows stay in the shared test DB (hidden, not deleted — that's the feature), so hide them again so no other test sees ~100 published products. */
    @AfterEach
    void hideMockDataAgain() {
        seeder.disable();
    }

    private StaffUser staffWithRole(String role) {
        StaffUser user = new StaffUser();
        user.setEmail("mock-toggle-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode("irrelevant-password"));
        user.setFirstName("Test");
        user.setLastName(role);
        user.setStaffRole(staffRoles.findByName(role).orElseThrow());
        return staffUsers.save(user);
    }

    private JsonNode call(boolean isPost, String path, String token) throws Exception {
        var request = isPost ? post(path) : get(path);
        MvcResult result = mvc.perform(request.header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String searchCatalogue(String query) throws Exception {
        return mvc.perform(get("/api/v1/catalog/variants").param("q", query))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void superAdminIsForbiddenFromEveryEndpointEvenThoughItHoldsTheSamePermissionsAsDeveloper() throws Exception {
        mvc.perform(get("/api/v1/admin/mock-data/status").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/mock-data/enable").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/mock-data/disable").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void enableGeneratesTheCatalogueDisableHidesItAndReEnableRestoresTheSameRows() throws Exception {
        JsonNode enabled = call(true, "/api/v1/admin/mock-data/enable", developerToken);
        assertThat(enabled.get("enabled").asBoolean()).isTrue();
        int productCount = enabled.get("productCount").asInt();
        assertThat(productCount).as("~100 mock products across the 6 real categories").isBetween(90, 120);
        assertThat(enabled.get("kitCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(enabled.get("professionCount").asInt()).isGreaterThanOrEqualTo(1);
        String generatedAt = enabled.get("generatedAt").asText();

        // The shopper-facing catalogue really shows them.
        assertThat(searchCatalogue("Chrome Mirror Gel Polish")).contains("Chrome Mirror Gel Polish");

        // The Flash Deals rail has something to show.
        JsonNode dealCards = json.readTree(mvc.perform(get("/api/v1/catalog/variants").param("deal", "true"))
                .andReturn().getResponse().getContentAsString());
        assertThat(dealCards.get("content").size()).as("demo flash deals").isGreaterThanOrEqualTo(8);

        // The Forecast tab has products to warn about.
        JsonNode forecastSummary = call(false, "/api/v1/stock/insights/summary", developerToken);
        assertThat(forecastSummary.get("needsAttention").asInt()).as("demo restock pressure").isGreaterThanOrEqualTo(10);

        JsonNode disabled = call(true, "/api/v1/admin/mock-data/disable", developerToken);
        assertThat(disabled.get("enabled").asBoolean()).isFalse();
        assertThat(disabled.get("productCount").asInt()).as("hidden, not deleted").isEqualTo(productCount);
        assertThat(products.findAll().stream().filter(p -> p.isMockData() && p.isPublished()).count()).isZero();

        assertThat(searchCatalogue("Chrome Mirror Gel Polish")).doesNotContain("Chrome Mirror Gel Polish");

        JsonNode reEnabled = call(true, "/api/v1/admin/mock-data/enable", developerToken);
        assertThat(reEnabled.get("enabled").asBoolean()).isTrue();
        assertThat(reEnabled.get("productCount").asInt()).as("no regeneration").isEqualTo(productCount);
        assertThat(reEnabled.get("generatedAt").asText()).isEqualTo(generatedAt);
    }

    /** Found live: the admin Products screen's default "all" filter sends published=null, which
     *  showed disabled mock products (isPublished=false) right alongside real unpublished ones —
     *  ProductRepository.searchAdmin now excludes mock rows outright unless currently enabled,
     *  regardless of the :published filter the screen has selected. */
    @Test
    void mockProductsOnlyAppearInTheAdminProductListWhileEnabled() throws Exception {
        call(true, "/api/v1/admin/mock-data/enable", developerToken);
        JsonNode enabledList = call(false, "/api/v1/products?size=200", developerToken);
        assertThat(enabledList.toString()).contains("Chrome Mirror Gel Polish");

        call(true, "/api/v1/admin/mock-data/disable", developerToken);
        JsonNode disabledList = call(false, "/api/v1/products?size=200", developerToken);
        assertThat(disabledList.toString()).doesNotContain("Chrome Mirror Gel Polish");
    }

    /** Found live alongside the product-list gap: CustomerOrder has no isPublished-equivalent field
     *  the way Product/Category do, so the 28 "MOCK-"-prefixed orders createFakeOrder() generates
     *  (as review/sale-event FK targets, never real checkouts) had NO gate at all — always visible
     *  in the admin orders list regardless of the toggle. CustomerOrderRepository.search now checks
     *  the same live "any mock product currently published" signal MockDataSeederService.status()
     *  already uses for "enabled". */
    @Test
    void mockOrdersOnlyAppearInTheAdminOrderListWhileEnabled() throws Exception {
        call(true, "/api/v1/admin/mock-data/enable", developerToken);
        JsonNode enabledList = call(false, "/api/v1/orders?size=200", developerToken);
        assertThat(enabledList.toString()).as("mock orders visible while enabled").contains("MOCK-");

        call(true, "/api/v1/admin/mock-data/disable", developerToken);
        JsonNode disabledList = call(false, "/api/v1/orders?size=200", developerToken);
        assertThat(disabledList.toString()).as("mock orders hidden once disabled").doesNotContain("MOCK-");
    }

    /** Found in the 2026-10-05 audit of where demo rows still surfaced: the staff Reviews list had no gate at
     *  all. The ~100 demo reviews are all APPROVED, so they showed under "Showing" and "All" with demo data
     *  switched off, and real reviews showed alongside them with it on. */
    @Test
    void demoReviewsOnlyAppearInTheStaffReviewListWhileEnabled() throws Exception {
        call(true, "/api/v1/admin/mock-data/enable", developerToken);
        JsonNode whileOn = call(false, "/api/v1/admin/reviews?size=200", developerToken);
        assertThat(whileOn.get("content").size()).as("demo reviews visible while enabled").isGreaterThan(0);
        assertThat(reviewedProductsThatAreDemo(whileOn)).as("only demo reviews while enabled").containsExactly(true);

        call(true, "/api/v1/admin/mock-data/disable", developerToken);
        JsonNode whileOff = call(false, "/api/v1/admin/reviews?size=200", developerToken);
        assertThat(reviewedProductsThatAreDemo(whileOff)).as("no demo reviews once disabled").doesNotContain(true);
        JsonNode approvedOff = call(false, "/api/v1/admin/reviews?status=APPROVED&size=200", developerToken);
        assertThat(reviewedProductsThatAreDemo(approvedOff)).as("the Showing tab too").doesNotContain(true);
    }

    /** The shopper-facing reviews list is keyed by product id; a hidden demo product's reviews must not be
     *  readable that way, exactly as its product page and its catalogue card are not. */
    @Test
    void aHiddenDemoProductsReviewsAreNotPubliclyReadableByItsId() throws Exception {
        call(true, "/api/v1/admin/mock-data/enable", developerToken);
        JsonNode reviews = call(false, "/api/v1/admin/reviews?status=APPROVED&size=200", developerToken).get("content");
        assertThat(reviews.size()).isGreaterThan(0);
        String productId = reviews.get(0).get("productId").asText();
        mvc.perform(get("/api/v1/catalog/products/" + productId + "/reviews")).andExpect(status().isOk());

        call(true, "/api/v1/admin/mock-data/disable", developerToken);
        mvc.perform(get("/api/v1/catalog/products/" + productId + "/reviews")).andExpect(status().isNotFound());
    }

    /** For each review on the page: is the product it is about a demo product? */
    private Set<Boolean> reviewedProductsThatAreDemo(JsonNode page) {
        Set<Boolean> flags = new HashSet<>();
        for (JsonNode review : page.get("content")) {
            UUID productId = UUID.fromString(review.get("productId").asText());
            flags.add(products.findById(productId).orElseThrow().isMockData());
        }
        return flags;
    }
}
