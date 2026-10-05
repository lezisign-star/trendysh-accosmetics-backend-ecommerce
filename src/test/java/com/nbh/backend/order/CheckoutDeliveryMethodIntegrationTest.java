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

/**
 * Checkout's delivery-method vocabulary went from BODA/VEHICLE to BODA/MATATU/COURIER
 * (V33/V34). Found live (2026-09-27): the DTO's {@code @Pattern} was updated but the
 * column's own {@code customer_order_delivery_method_check} constraint (V14) was not,
 * so a real checkout with MATATU or COURIER passed validation and then failed at the
 * database with a raw 409 — a class of bug a Mockito-based unit test can't see, since
 * nothing there ever touches a real Postgres constraint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckoutDeliveryMethodIntegrationTest {

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
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Published (not just drafted) — {@code CartService.addItem} refuses an unpublished product's variant. */
    private String newVariantWithStock(String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return variantId;
    }

    private String freshCartSessionWith(String variantId) throws Exception {
        String sessionId = UUID.randomUUID().toString();
        MvcResult result = mvc.perform(post("/api/v1/cart/items")
                        .header("X-Cart-Session", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"qty\":1}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return sessionId;
    }

    private MvcResult checkout(String sessionId, String deliveryMethod) throws Exception {
        String body = """
                {"customerName":"Test Customer","guestEmail":"%s",
                 "guestPhone":"%s","fulfilmentType":"DELIVERY_COURIER",
                 "deliveryAddress":"Kilimani, Nairobi","deliveryMethod":"%s"}
                """.formatted(TestContacts.email(), TestContacts.phone(), deliveryMethod);
        return mvc.perform(post("/api/v1/checkout")
                        .header("X-Cart-Session", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private void assertMethodAcceptedAndPersisted(String method) throws Exception {
        String variantId = newVariantWithStock("Delivery Method Test " + UUID.randomUUID().toString().substring(0, 8));
        String sessionId = freshCartSessionWith(variantId);

        MvcResult result = checkout(sessionId, method);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String orderId = json.readTree(result.getResponse().getContentAsString()).get("order").get("id").asText();

        JsonNode detail = okStaff(get("/api/v1/orders/" + orderId));
        assertThat(detail.get("deliveryMethod").asText()).isEqualTo(method);
    }

    @Test
    void bodaIsAcceptedAndPersisted() throws Exception {
        assertMethodAcceptedAndPersisted("BODA");
    }

    @Test
    void matatuIsAcceptedAndPersisted() throws Exception {
        assertMethodAcceptedAndPersisted("MATATU");
    }

    @Test
    void courierIsAcceptedAndPersisted() throws Exception {
        assertMethodAcceptedAndPersisted("COURIER");
    }

    @Test
    void theRetiredVehicleValueIsNowRejected() throws Exception {
        String variantId = newVariantWithStock("Retired Method Test " + UUID.randomUUID().toString().substring(0, 8));
        String sessionId = freshCartSessionWith(variantId);

        assertThat(checkout(sessionId, "VEHICLE").getResponse().getStatus()).isEqualTo(400);
    }

    /** There are three shops to pick up from, so the order must say which one it waits at; a delivery
     *  order's address stays out of the customer view. */
    @Test
    void aPickupOrderNamesTheShopItWaitsAtButADeliveryOrderKeepsItsAddressPrivate() throws Exception {
        String pickupSession = freshCartSessionWith(
                newVariantWithStock("Pickup Place Test " + UUID.randomUUID().toString().substring(0, 8)));
        String pickupBody = """
                {"customerName":"Test Customer","guestEmail":"%s","guestPhone":"%s",
                 "fulfilmentType":"PICKUP","deliveryAddress":"Simara Mall — Shop GS 07"}
                """.formatted(TestContacts.email(), TestContacts.phone());
        MvcResult pickup = mvc.perform(post("/api/v1/checkout")
                        .header("X-Cart-Session", pickupSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pickupBody))
                .andReturn();
        assertThat(pickup.getResponse().getStatus()).as(pickup.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode pickupOrder = json.readTree(pickup.getResponse().getContentAsString()).get("order");
        assertThat(pickupOrder.get("pickupPlace").asText()).isEqualTo("Simara Mall — Shop GS 07");

        String deliverySession = freshCartSessionWith(
                newVariantWithStock("Delivery Place Test " + UUID.randomUUID().toString().substring(0, 8)));
        MvcResult delivery = checkout(deliverySession, "BODA");
        assertThat(delivery.getResponse().getStatus()).as(delivery.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode deliveryOrder = json.readTree(delivery.getResponse().getContentAsString()).get("order");
        assertThat(deliveryOrder.get("pickupPlace").isNull()).isTrue();
    }
}
