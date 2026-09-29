package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flash deals set in bulk from the admin product screens ({@code POST /products/flash-deal}): a
 * percentage off every sellable variant until a deadline, whole shillings, and an "end" that clears
 * them. Unique names per run so it can share the database with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlashDealBulkIntegrationTest {

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

    /** A published product with a 1000-shilling variant and a 750-shilling one. */
    private JsonNode newProduct(String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":true,\"variants\":["
                + "{\"variantName\":\"Large\",\"sellingPrice\":\"1000\",\"isActive\":true},"
                + "{\"variantName\":\"Small\",\"sellingPrice\":\"750\",\"isActive\":true}]}";
        return ok(post("/api/v1/products/draft"), body);
    }

    private static String in(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).toString();
    }

    private static double dealPriceOf(JsonNode product, String variantName) {
        for (JsonNode v : product.get("variants")) {
            if (variantName.equals(v.get("variantName").asText())) {
                return v.get("dealPrice").isNull() ? -1 : v.get("dealPrice").asDouble();
            }
        }
        throw new AssertionError("no variant " + variantName);
    }

    @Test
    void startingAFlashDealDiscountsEverySellableVariantAndEndingItClearsThem() throws Exception {
        String name = "Flash Deal Serum " + UUID.randomUUID().toString().substring(0, 8);
        String id = newProduct(name).get("id").asText();
        String ids = "[\"" + id + "\"]";

        JsonNode started = ok(post("/api/v1/products/flash-deal"),
                "{\"productIds\":" + ids + ",\"percentOff\":20,\"endsAt\":\"" + in(3) + "\"}");
        assertThat(started.get("affectedProducts").asInt()).isEqualTo(1);
        assertThat(started.get("affectedVariants").asInt()).isEqualTo(2);

        JsonNode product = ok(get("/api/v1/products/" + id), null);
        assertThat(dealPriceOf(product, "Large")).isEqualTo(800.0);
        assertThat(dealPriceOf(product, "Small")).isEqualTo(600.0);

        // The shopper-facing catalogue sees it as a live deal.
        JsonNode cards = json.readTree(mvc.perform(get("/api/v1/catalog/variants").param("q", name))
                .andReturn().getResponse().getContentAsString());
        assertThat(cards.get("content")).isNotEmpty();
        for (JsonNode card : cards.get("content")) {
            assertThat(card.get("onDeal").asBoolean()).isTrue();
        }

        JsonNode ended = ok(post("/api/v1/products/flash-deal/end"), "{\"productIds\":" + ids + "}");
        assertThat(ended.get("affectedVariants").asInt()).isEqualTo(2);
        product = ok(get("/api/v1/products/" + id), null);
        assertThat(dealPriceOf(product, "Large")).isEqualTo(-1);
        assertThat(dealPriceOf(product, "Small")).isEqualTo(-1);
    }

    @Test
    void aSecondFlashDealReplacesTheFirst() throws Exception {
        String id = newProduct("Flash Deal Replace " + UUID.randomUUID().toString().substring(0, 8)).get("id").asText();
        String ids = "[\"" + id + "\"]";

        ok(post("/api/v1/products/flash-deal"), "{\"productIds\":" + ids + ",\"percentOff\":10,\"endsAt\":\"" + in(2) + "\"}");
        ok(post("/api/v1/products/flash-deal"), "{\"productIds\":" + ids + ",\"percentOff\":30,\"endsAt\":\"" + in(5) + "\"}");

        assertThat(dealPriceOf(ok(get("/api/v1/products/" + id), null), "Large")).isEqualTo(700.0);
    }

    @Test
    void anUnpricedVariantIsSkippedWithAWarningRatherThanFailingTheWholeBatch() throws Exception {
        String body = "{\"name\":\"Flash Deal Partial " + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"categoryIds\":[],\"published\":true,\"variants\":["
                + "{\"variantName\":\"Priced\",\"sellingPrice\":\"500\",\"isActive\":true},"
                + "{\"variantName\":\"Unpriced\",\"isActive\":true}]}";
        String id = ok(post("/api/v1/products/draft"), body).get("id").asText();

        JsonNode result = ok(post("/api/v1/products/flash-deal"),
                "{\"productIds\":[\"" + id + "\"],\"percentOff\":10,\"endsAt\":\"" + in(1) + "\"}");
        assertThat(result.get("affectedVariants").asInt()).isEqualTo(1);
        assertThat(result.get("warnings").toString()).contains("no price");
        assertThat(dealPriceOf(ok(get("/api/v1/products/" + id), null), "Priced")).isEqualTo(450.0);
    }

    @Test
    void badInputIsRejected() throws Exception {
        String id = newProduct("Flash Deal Invalid " + UUID.randomUUID().toString().substring(0, 8)).get("id").asText();
        String ids = "[\"" + id + "\"]";

        assertThat(call(post("/api/v1/products/flash-deal"),
                "{\"productIds\":" + ids + ",\"percentOff\":0,\"endsAt\":\"" + in(1) + "\"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(call(post("/api/v1/products/flash-deal"),
                "{\"productIds\":" + ids + ",\"percentOff\":95,\"endsAt\":\"" + in(1) + "\"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(call(post("/api/v1/products/flash-deal"),
                "{\"productIds\":" + ids + ",\"percentOff\":20,\"endsAt\":\"" + in(-1) + "\"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(call(post("/api/v1/products/flash-deal"),
                "{\"productIds\":[],\"percentOff\":20,\"endsAt\":\"" + in(1) + "\"}")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aViewerCannotStartOrEndDeals() throws Exception {
        StaffUser viewer = new StaffUser();
        viewer.setEmail("viewer-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        viewer.setPassword(passwordEncoder.encode("irrelevant-password"));
        viewer.setFirstName("Read");
        viewer.setLastName("Only");
        viewer.setStaffRole(staffRoles.findByName("VIEWER").orElseThrow());
        String viewerToken = jwtService.generateAccessToken(staffUsers.save(viewer));

        String body = "{\"productIds\":[\"" + UUID.randomUUID() + "\"],\"percentOff\":20,\"endsAt\":\"" + in(1) + "\"}";
        mvc.perform(post("/api/v1/products/flash-deal").header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/products/flash-deal/end").header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/products/flash-deal").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
