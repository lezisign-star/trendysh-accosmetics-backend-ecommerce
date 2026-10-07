package com.nbh.backend.delivery;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Staff-managed delivery places and prices, what customers see of them, and the customer suggestion queue. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeliveryPlacesIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;

    private String token;

    @BeforeEach
    void signIn() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, boolean staff) throws Exception {
        if (staff) request.header("Authorization", "Bearer " + token);
        MvcResult r = mvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String body = r.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private int status(MockHttpServletRequestBuilder request, boolean staff) throws Exception {
        if (staff) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder r, String b) {
        return r.contentType(MediaType.APPLICATION_JSON).content(b);
    }

    private static String place(String name, boolean active, String prices) {
        return "{\"name\":\"" + name + "\",\"region\":\"Kiambu\",\"active\":" + active + ",\"prices\":" + prices + "}";
    }

    private static final String ALL_THREE = "[{\"method\":\"METRO\",\"priceMin\":100,\"priceMax\":150,\"etaText\":\"Same day\"},"
            + "{\"method\":\"BODA\",\"priceMin\":300,\"priceMax\":450},{\"method\":\"MATATU\",\"priceMin\":200,\"priceMax\":200}]";

    private String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aPlaceWithThreePriceOptionsIsSearchableByCustomersWithoutLogin() throws Exception {
        String name = unique("Ruaka");
        JsonNode created = ok(body(post("/api/v1/admin/delivery/places"), place(name, true, ALL_THREE)), true);
        assertThat(created.get("prices")).hasSize(3);

        JsonNode found = ok(get("/api/v1/public/delivery/places").param("q", name.toLowerCase()), false);
        assertThat(found).hasSize(1);
        JsonNode prices = found.get(0).get("prices");
        assertThat(prices.get(0).get("method").asText()).isEqualTo("METRO");
        assertThat(prices.get(0).get("priceMin").asDouble()).isEqualTo(100.0);
        assertThat(prices.get(1).get("priceMax").asDouble()).isEqualTo(450.0);
    }

    @Test
    void aHiddenPlaceStaysOutOfTheCustomerListButIsStillManageable() throws Exception {
        String name = unique("Hidden");
        JsonNode created = ok(body(post("/api/v1/admin/delivery/places"), place(name, false, ALL_THREE)), true);

        assertThat(ok(get("/api/v1/public/delivery/places").param("q", name), false)).isEmpty();
        assertThat(ok(get("/api/v1/admin/delivery/places").param("q", name), true)).hasSize(1);

        ok(body(put("/api/v1/admin/delivery/places/" + created.get("id").asText()), place(name, true, ALL_THREE)), true);
        assertThat(ok(get("/api/v1/public/delivery/places").param("q", name), false)).hasSize(1);
    }

    @Test
    void editingReplacesThePriceSetAndDeletingRemovesThePlace() throws Exception {
        String name = unique("Editable");
        String id = ok(body(post("/api/v1/admin/delivery/places"), place(name, true, ALL_THREE)), true).get("id").asText();

        JsonNode updated = ok(body(put("/api/v1/admin/delivery/places/" + id),
                place(name, true, "[{\"method\":\"MATATU\",\"priceMin\":250,\"priceMax\":300}]")), true);
        assertThat(updated.get("prices")).hasSize(1);
        assertThat(updated.get("prices").get(0).get("priceMin").asDouble()).isEqualTo(250.0);

        assertThat(status(delete("/api/v1/admin/delivery/places/" + id), true)).isEqualTo(204);
        assertThat(ok(get("/api/v1/public/delivery/places").param("q", name), false)).isEmpty();
    }

    @Test
    void badPricesAndDuplicateNamesAreRefused() throws Exception {
        String name = unique("Rules");
        // highest below lowest
        assertThat(status(body(post("/api/v1/admin/delivery/places"),
                place(name, true, "[{\"method\":\"BODA\",\"priceMin\":500,\"priceMax\":100}]")), true)).isEqualTo(409);
        // same way twice
        assertThat(status(body(post("/api/v1/admin/delivery/places"), place(name, true,
                "[{\"method\":\"BODA\",\"priceMin\":1,\"priceMax\":2},{\"method\":\"BODA\",\"priceMin\":1,\"priceMax\":2}]")), true))
                .isEqualTo(409);
        // negative price
        assertThat(status(body(post("/api/v1/admin/delivery/places"),
                place(name, true, "[{\"method\":\"BODA\",\"priceMin\":-5,\"priceMax\":2}]")), true)).isEqualTo(400);
        // duplicate name, case-insensitively
        ok(body(post("/api/v1/admin/delivery/places"), place(name, true, ALL_THREE)), true);
        assertThat(status(body(post("/api/v1/admin/delivery/places"), place(name.toUpperCase(), true, ALL_THREE)), true))
                .isEqualTo(409);
    }

    @Test
    void managingPlacesRequiresStaffSignIn() throws Exception {
        assertThat(status(get("/api/v1/admin/delivery/places"), false)).isIn(401, 403);
        assertThat(status(body(post("/api/v1/admin/delivery/places"), place("x", true, "[]")), false)).isIn(401, 403);
    }

    @Test
    void aCustomerSuggestionReachesTheQueueAndCanBeApprovedOrDismissed() throws Exception {
        long before = ok(get("/api/v1/admin/delivery/suggestions/count"), true).get("count").asLong();
        String name = unique("Thika Greens");
        assertThat(status(body(post("/api/v1/public/delivery/suggestions"),
                "{\"name\":\"" + name + "\",\"details\":\"near the roundabout\",\"contact\":\"0700111222\"}"), false))
                .isEqualTo(202);

        assertThat(ok(get("/api/v1/admin/delivery/suggestions/count"), true).get("count").asLong()).isEqualTo(before + 1);
        JsonNode queue = ok(get("/api/v1/admin/delivery/suggestions"), true);
        String id = null;
        for (JsonNode s : queue) if (name.equals(s.get("name").asText())) id = s.get("id").asText();
        assertThat(id).isNotNull();

        JsonNode approved = ok(body(post("/api/v1/admin/delivery/suggestions/" + id + "/approve"), "{\"note\":\"Added\"}"), true);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        // handled once only
        assertThat(status(post("/api/v1/admin/delivery/suggestions/" + id + "/reject"), true)).isEqualTo(409);
        assertThat(ok(get("/api/v1/admin/delivery/suggestions/count"), true).get("count").asLong()).isEqualTo(before);
    }

    @Test
    void theSamePlaceAskedForTwiceIsOneItemInTheQueueNoMatterTheCapitals() throws Exception {
        String name = unique("Ruiru Kimbo");
        for (String typed : new String[]{name, name.toUpperCase(), "  " + name.toLowerCase() + "  "}) {
            assertThat(status(body(post("/api/v1/public/delivery/suggestions").with(r -> {
                r.setRemoteAddr("203.0.113." + (10 + Math.abs(typed.hashCode() % 200)));
                return r;
            }), "{\"name\":\"" + typed + "\"}"), false)).isEqualTo(202);
        }

        long copies = 0;
        for (JsonNode s : ok(get("/api/v1/admin/delivery/suggestions"), true)) {
            if (name.equalsIgnoreCase(s.get("name").asText())) copies++;
        }
        assertThat(copies).isEqualTo(1);
    }

    @Test
    void anEmptySuggestionIsRejectedAndAnyoneAbuseIsThrottled() throws Exception {
        assertThat(status(body(post("/api/v1/public/delivery/suggestions"), "{\"name\":\"  \"}"), false)).isEqualTo(400);

        // Six more from one client inside an hour: the first five land, the sixth is throttled.
        int throttled = 0;
        for (int i = 0; i < 6; i++) {
            int s = mvc.perform(post("/api/v1/public/delivery/suggestions").with(r -> {
                        r.setRemoteAddr("203.0.113.77");
                        return r;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Spam " + UUID.randomUUID() + "\"}")).andReturn().getResponse().getStatus();
            if (s == 429) throttled++;
        }
        assertThat(throttled).isGreaterThanOrEqualTo(1);
    }
}
