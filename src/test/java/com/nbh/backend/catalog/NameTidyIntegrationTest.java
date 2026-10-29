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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** "Tidy names" (owner, 2026-10-29): only the capitals of a name may change, for products in the shop too. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NameTidyIntegrationTest {

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

    @Test
    void namesInCapitalsAreSavedTidyAndOtherWordsAreRefused() throws Exception {
        String code = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String loud = "CUTICLE OIL " + code + " 15ML";
        JsonNode product = ok(post("/api/v1/products/draft"), "{\"name\":\"" + loud + "\",\"categoryIds\":[],"
                + "\"published\":true,\"variants\":[{\"variantName\":\"" + loud + "\",\"sellingPrice\":\"450\",\"isActive\":true}]}");
        String id = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        String tidy = "Cuticle Oil " + code + " 15ml";

        JsonNode result = ok(post("/api/v1/products/tidy-names"), "{\"products\":[{\"productId\":\"" + id + "\",\"name\":\""
                + tidy + "\",\"variants\":[{\"id\":\"" + variantId + "\",\"variantName\":\"" + tidy + "\"}]}]}");
        assertThat(result.get("products").asInt()).isEqualTo(1);
        assertThat(result.get("variants").asInt()).isEqualTo(1);
        JsonNode after = ok(get("/api/v1/products/" + id), null);
        assertThat(after.get("name").asText()).isEqualTo(tidy);
        assertThat(after.get("variants").get(0).get("variantName").asText()).isEqualTo(tidy);
        assertThat(after.get("isPublished").asBoolean()).isTrue();

        MvcResult other = call(post("/api/v1/products/tidy-names"), "{\"products\":[{\"productId\":\"" + id
                + "\",\"name\":\"Cuticle Serum " + code + "\"}]}");
        assertThat(other.getResponse().getStatus()).isEqualTo(422);
        assertThat(ok(get("/api/v1/products/" + id), null).get("name").asText()).isEqualTo(tidy);
    }
}
