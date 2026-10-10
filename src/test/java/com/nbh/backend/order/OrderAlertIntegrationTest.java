package com.nbh.backend.order;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The new-order ring (2026-10-10): a placed order rings until a member of staff stops it, and stopping it is
 * recorded on the order so it stops for everyone, not just the tab that was tapped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderAlertIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;

    private String staffToken;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    private JsonNode okStaff(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private String placeOrder() throws Exception {
        String name = "Ring Test " + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));

        String session = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + variantId + "\",\"qty\":1}")).andReturn();
        String checkout = """
                {"customerName":"Ring Customer","guestEmail":"%s","guestPhone":"%s","fulfilmentType":"PICKUP",
                 "deliveryAddress":"Junction"}
                """.formatted(TestContacts.email(), TestContacts.phone());
        MvcResult result = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content(checkout)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).get("order").get("orderNo").asText();
    }

    private boolean listed(JsonNode waiting, String orderNo) {
        for (JsonNode o : waiting.get("orders")) {
            if (orderNo.equals(o.get("orderNo").asText())) return true;
        }
        return false;
    }

    @Test
    void aNewOrderRingsUntilStaffStopItAndThenStaysQuiet() throws Exception {
        okStaff(post("/api/v1/order-alerts/stop"));
        assertThat(okStaff(get("/api/v1/order-alerts")).get("count").asInt()).isZero();

        String orderNo = placeOrder();
        JsonNode waiting = okStaff(get("/api/v1/order-alerts"));
        assertThat(waiting.get("count").asInt()).isEqualTo(1);
        assertThat(listed(waiting, orderNo)).isTrue();

        // A second look does not stop it: only a tap does.
        assertThat(okStaff(get("/api/v1/order-alerts")).get("count").asInt()).isEqualTo(1);

        JsonNode stopped = okStaff(post("/api/v1/order-alerts/stop").contentType(MediaType.APPLICATION_JSON)
                .content("{\"upTo\":\"" + waiting.get("upTo").asText() + "\"}"));
        assertThat(stopped.get("stopped").asInt()).isEqualTo(1);
        assertThat(okStaff(get("/api/v1/order-alerts")).get("count").asInt()).isZero();
    }

    @Test
    void anOrderThatArrivesAfterTheListWasReadKeepsRinging() throws Exception {
        okStaff(post("/api/v1/order-alerts/stop"));
        String first = placeOrder();
        JsonNode waiting = okStaff(get("/api/v1/order-alerts"));
        assertThat(listed(waiting, first)).isTrue();
        String upTo = waiting.get("upTo").asText();

        String second = placeOrder();
        okStaff(post("/api/v1/order-alerts/stop").contentType(MediaType.APPLICATION_JSON)
                .content("{\"upTo\":\"" + upTo + "\"}"));

        JsonNode after = okStaff(get("/api/v1/order-alerts"));
        assertThat(listed(after, first)).isFalse();
        assertThat(listed(after, second)).isTrue();
    }
}
