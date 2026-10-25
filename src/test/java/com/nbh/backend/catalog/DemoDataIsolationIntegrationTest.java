package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The demo-data switch is strictly one or the other: while it is off every screen shows only real data, while it
 * is on only the demo catalogue — and switching back restores exactly the real figures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoDataIsolationIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;

    private String developer;

    @BeforeEach
    void signIn() {
        StaffUser user = new StaffUser();
        user.setEmail("demo-dev-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode("Irrelevant123!"));
        user.setFirstName("Demo");
        user.setLastName("Dev");
        user.setStaffRole(roles.findByName("DEVELOPER").orElseThrow());
        developer = jwtService.generateAccessToken(staffUsers.save(user));
    }

    @AfterEach
    void leaveDemoDataOff() throws Exception {
        call(post("/api/v1/admin/mock-data/disable"));
    }

    private JsonNode call(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + developer);
        MvcResult r = mvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(request.toString() + " " + r.getResponse().getContentAsString())
                .isEqualTo(200);
        String body = r.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private record Snapshot(boolean demoOn, long orders, long adminProducts, long storefrontVariants, long stockLines,
                            long customers, long packages, long categories) {}

    private java.util.Set<String> categoryNames() throws Exception {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (JsonNode c : call(get("/api/v1/categories"))) {
            names.add(c.get("name").asText());
        }
        return names;
    }

    private Snapshot snapshot() throws Exception {
        JsonNode status = call(get("/api/v1/admin/mock-data/status"));
        return new Snapshot(
                status.get("enabled").asBoolean(),
                call(get("/api/v1/admin/analytics/summary")).get("totalOrders").asLong(),
                call(get("/api/v1/products").param("size", "1")).get("totalElements").asLong(),
                call(get("/api/v1/catalog/variants").param("size", "1")).get("totalElements").asLong(),
                call(get("/api/v1/stock/inventory").param("size", "1")).get("totalElements").asLong(),
                call(get("/api/v1/admin/customers").param("size", "1")).get("totalElements").asLong(),
                call(get("/api/v1/admin/packages").param("size", "1")).get("totalElements").asLong(),
                call(get("/api/v1/categories")).size());
    }

    @Test
    void demoOnShowsOnlyDemoAndDemoOffReturnsExactlyTheRealFigures() throws Exception {
        call(post("/api/v1/admin/mock-data/disable"));
        Snapshot real = snapshot();
        java.util.Set<String> realCategoryNames = categoryNames();
        assertThat(real.demoOn()).isFalse();

        call(post("/api/v1/admin/mock-data/enable"));
        Snapshot demo = snapshot();
        assertThat(demo.demoOn()).isTrue();
        // Demo mode is its own data set: the 28 placeholder orders, ~100 products and 5 demo packages — not real + demo.
        assertThat(demo.orders()).isEqualTo(28);
        assertThat(demo.adminProducts()).isGreaterThan(50);
        assertThat(demo.storefrontVariants()).isGreaterThan(50);
        assertThat(demo.packages()).isEqualTo(5);
        assertThat(demo.customers()).isEqualTo(28);
        assertThat(categoryNames()).as("demo subcategories replace the real ones").isNotEqualTo(realCategoryNames);
        assertThat(demo.adminProducts()).isNotEqualTo(real.adminProducts());

        call(post("/api/v1/admin/mock-data/disable"));
        Snapshot back = snapshot();
        assertThat(back).isEqualTo(real);
    }

    @Test
    void whileDemoDataIsOffNoPlaceholderOrderOrCustomerIsVisible() throws Exception {
        call(post("/api/v1/admin/mock-data/enable"));
        call(post("/api/v1/admin/mock-data/disable"));

        JsonNode orders = call(get("/api/v1/orders").param("size", "200"));
        for (JsonNode o : orders.get("content")) {
            assertThat(o.get("orderNo").asText()).doesNotStartWith("MOCK-");
        }
        JsonNode customers = call(get("/api/v1/admin/customers").param("size", "200"));
        assertThat(customers.get("content")).noneMatch(c -> c.get("email").asText().contains("mock"));
        assertThat(call(get("/api/v1/admin/analytics/daily").param("days", "30")).toString()).isNotBlank();
    }
}
