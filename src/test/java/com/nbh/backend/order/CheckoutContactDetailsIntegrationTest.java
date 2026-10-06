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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Found in the 2026-10-06 go-live QA: the checkout form insists on a real Kenyan mobile number and a delivery
 * address, but the API behind it accepted "12345", "abc" and a delivery with nowhere to deliver to, and answered 500
 * when the cart-session header was missing. Staff ring the customer on that number to settle payment and delivery,
 * so an order with a made-up one is an order nobody can act on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckoutContactDetailsIntegrationTest {

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

    private String newVariantWithStock() throws Exception {
        String body = "{\"name\":\"Contact Test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":50,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return variantId;
    }

    private String cartSessionWith(String variantId) throws Exception {
        String sessionId = UUID.randomUUID().toString();
        MvcResult result = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + variantId + "\",\"qty\":1}")).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return sessionId;
    }

    private MvcResult checkout(String sessionId, String phone, String type, String address, String dropoffContact) throws Exception {
        String addressJson = address == null ? "null" : "\"" + address + "\"";
        String contactJson = dropoffContact == null ? "null" : "\"" + dropoffContact + "\"";
        String body = """
                {"customerName":"Test Customer","guestEmail":"%s","guestPhone":"%s","fulfilmentType":"%s",
                 "deliveryAddress":%s,"deliveryMethod":"MATATU","deliveryContactPhone":%s}
                """.formatted(TestContacts.email(), phone, type, addressJson, contactJson);
        return mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", sessionId)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    @Test
    void aPhoneNumberStaffCouldNotRingIsRefusedAndTheCartIsLeftAloneToTryAgain() throws Exception {
        String session = cartSessionWith(newVariantWithStock());

        for (String bad : new String[] {"12345", "abc", "0720 000", "0220000111", "+255720000111", "07200001112"}) {
            MvcResult refused = checkout(session, bad, "PICKUP", "Junction — Stalls D5", null);
            assertThat(statusOf(refused)).as("phone '" + bad + "': " + refused.getResponse().getContentAsString()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("valid Kenyan mobile number");
        }

        // None of those touched the cart: the same session still checks out once the number is right.
        assertThat(statusOf(checkout(session, TestContacts.phone(), "PICKUP", "Junction — Stalls D5", null))).isEqualTo(200);
    }

    @Test
    void everySpellingOfARealNumberIsAccepted() throws Exception {
        String variant = newVariantWithStock();
        // Fresh numbers every run, written four ways: a checkout caps the unpaid orders per number, and every test
        // in the suite shares one database, so a fixed number would soon belong to somebody else's orders.
        String spaced = TestContacts.phone();                                       // 07xxxxxxxx -> 0712 345 678
        String international = TestContacts.phone();                                // 07xxxxxxxx -> +254 712 345 678
        String bare = TestContacts.phone();                                         // 07xxxxxxxx -> 254712345678
        String newerPrefix = "01" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000));
        String[] spellings = {
                spaced.substring(0, 4) + " " + spaced.substring(4, 7) + " " + spaced.substring(7),
                newerPrefix.substring(0, 4) + "-" + newerPrefix.substring(4, 7) + "-" + newerPrefix.substring(7),
                "+254 " + international.substring(1, 4) + " " + international.substring(4, 7) + " " + international.substring(7),
                "254" + bare.substring(1)};
        for (String good : spellings) {
            MvcResult placed = checkout(cartSessionWith(variant), good, "PICKUP", "Superior Arcade — Shop B33 and B10", null);
            assertThat(statusOf(placed)).as("phone '" + good + "': " + placed.getResponse().getContentAsString()).isEqualTo(200);
        }
    }

    @Test
    void theNumberOfWhoeverReceivesTheDeliveryIsHeldToTheSameStandard() throws Exception {
        String variant = newVariantWithStock();

        MvcResult refused = checkout(cartSessionWith(variant), TestContacts.phone(), "DELIVERY_COURIER", "Kilimani, Nairobi", "banana");
        assertThat(statusOf(refused)).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("receiving the delivery");

        assertThat(statusOf(checkout(cartSessionWith(variant), TestContacts.phone(), "DELIVERY_COURIER", "Kilimani, Nairobi", "0711 222 333"))).isEqualTo(200);
        assertThat(statusOf(checkout(cartSessionWith(variant), TestContacts.phone(), "DELIVERY_COURIER", "Kilimani, Nairobi", ""))).as("blank means same as the order phone").isEqualTo(200);
    }

    @Test
    void aDeliveryNeedsSomewhereToGoButAPickupDoesNotNeedAnAddress() throws Exception {
        String variant = newVariantWithStock();

        for (String noAddress : new String[] {null, "", "   "}) {
            MvcResult refused = checkout(cartSessionWith(variant), TestContacts.phone(), "DELIVERY_COURIER", noAddress, null);
            assertThat(statusOf(refused)).as("address " + noAddress).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("where to deliver");
        }
        assertThat(statusOf(checkout(cartSessionWith(variant), TestContacts.phone(), "PICKUP", null, null))).isEqualTo(200);
    }

    @Test
    void aMissingCartSessionHeaderIsABadRequestNotAServerError() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"T\",\"guestEmail\":\"" + TestContacts.email() + "\",\"guestPhone\":\""
                        + TestContacts.phone() + "\",\"fulfilmentType\":\"PICKUP\"}")).andReturn();

        assertThat(statusOf(result)).as(result.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("X-Cart-Session");
    }
}
