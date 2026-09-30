package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A variant's wholesale tier ({@code PUT/DELETE /products/{id}/variants/{variantId}/wholesale}):
 * a minimum quantity and a per-unit price that applies to the whole cart line once reached. Same
 * set/clear pattern and PERM_PRODUCT_MANAGE gate as the flash-deal endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WholesalePricingIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;

    private String token;

    @BeforeEach
    void signInAsSuperAdmin() {
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
        MvcResult result = call(request, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private record Ids(String productId, String variantId) {}

    private Ids newVariant(String price) throws Exception {
        String body = "{\"name\":\"Wholesale " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"" + price
                + "\",\"isActive\":true}]}";
        JsonNode product = ok(post("/api/v1/products/draft"), body);
        return new Ids(product.get("id").asText(), product.get("variants").get(0).get("id").asText());
    }

    @Test
    void settingAndClearingAWholesaleTierPersistsAndReadsBackCorrectly() throws Exception {
        Ids ids = newVariant("1000");

        JsonNode set = ok(put("/api/v1/products/" + ids.productId() + "/variants/" + ids.variantId() + "/wholesale"),
                "{\"minQty\":10,\"wholesalePrice\":\"700\"}");
        assertThat(set.get("wholesaleMinQty").asInt()).isEqualTo(10);
        assertThat(set.get("wholesalePrice").decimalValue()).isEqualByComparingTo("700");

        JsonNode cleared = ok(delete("/api/v1/products/" + ids.productId() + "/variants/" + ids.variantId() + "/wholesale"), null);
        assertThat(cleared.get("wholesaleMinQty").isNull()).isTrue();
        assertThat(cleared.get("wholesalePrice").isNull()).isTrue();
    }

    @Test
    void aWholesalePriceThatIsNotBelowTheSellingPriceIsRejected() throws Exception {
        Ids ids = newVariant("1000");

        assertThat(call(put("/api/v1/products/" + ids.productId() + "/variants/" + ids.variantId() + "/wholesale"),
                "{\"minQty\":10,\"wholesalePrice\":\"1000\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(call(put("/api/v1/products/" + ids.productId() + "/variants/" + ids.variantId() + "/wholesale"),
                "{\"minQty\":10,\"wholesalePrice\":\"1200\"}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aZeroOrNegativeWholesalePriceIsRejected() throws Exception {
        Ids ids = newVariant("1000");

        assertThat(call(put("/api/v1/products/" + ids.productId() + "/variants/" + ids.variantId() + "/wholesale"),
                "{\"minQty\":10,\"wholesalePrice\":\"0\"}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aMinQtyOfOneIsRejected() throws Exception {
        Ids ids = newVariant("1000");

        assertThat(call(put("/api/v1/products/" + ids.productId() + "/variants/" + ids.variantId() + "/wholesale"),
                "{\"minQty\":1,\"wholesalePrice\":\"700\"}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aViewerCannotSetOrClearAWholesaleTier() throws Exception {
        Ids ids = newVariant("1000");
        StaffUser viewer = new StaffUser();
        viewer.setEmail("viewer-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        viewer.setPassword(passwordEncoder.encode("irrelevant-password"));
        viewer.setFirstName("Read");
        viewer.setLastName("Only");
        viewer.setStaffRole(staffRoles.findByName("VIEWER").orElseThrow());
        String viewerToken = jwtService.generateAccessToken(staffUsers.save(viewer));

        mvc.perform(put("/api/v1/products/" + ids.productId() + "/variants/" + ids.variantId() + "/wholesale")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minQty\":10,\"wholesalePrice\":\"700\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/products/" + ids.productId() + "/variants/" + ids.variantId() + "/wholesale")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/products/" + ids.productId() + "/variants/" + ids.variantId() + "/wholesale")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minQty\":10,\"wholesalePrice\":\"700\"}"))
                .andExpect(status().isForbidden());
    }
}
