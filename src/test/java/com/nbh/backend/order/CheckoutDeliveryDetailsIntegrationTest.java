package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 2026-10-07, owner's request: "more delivery location details ... so that the customer can specify even more, even if
 * they will be optional". A delivery can carry where exactly, how to find it, who receives it and when; staff see it in
 * the order panel. Which of them an order keeps depends on how it travels, and a pickup keeps none.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckoutDeliveryDetailsIntegrationTest {

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
        String body = "{\"name\":\"Details Test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
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

    private String cartSession() throws Exception {
        String variantId = newVariantWithStock();
        String sessionId = UUID.randomUUID().toString();
        MvcResult result = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + variantId + "\",\"qty\":1}")).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return sessionId;
    }

    private ObjectNode details() {
        return json.createObjectNode()
                .put("estate", "Kilimani")
                .put("street", "Argwings Kodhek Rd")
                .put("building", "Yaya Towers")
                .put("unit", "4th floor, door 12")
                .put("courierOffice", "Easy Coach Nakuru")
                .put("directions", "Opposite the pharmacy, blue gate")
                .put("instructions", "Call at the gate")
                .put("receiverName", "Njeri Receptionist")
                .put("timeWindow", "MORNING");
    }

    private MvcResult checkout(String type, String method, ObjectNode deliveryDetails) throws Exception {
        ObjectNode body = json.createObjectNode()
                .put("customerName", "Test Customer")
                .put("guestEmail", TestContacts.email())
                .put("guestPhone", TestContacts.phone())
                .put("fulfilmentType", type)
                .put("deliveryAddress", "Kilimani, Nairobi")
                .put("deliveryMethod", method)
                .put("deliveryContactPhone", "0711 222 333");
        if (deliveryDetails != null) {
            body.set("deliveryDetails", deliveryDetails);
        }
        return mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", cartSession())
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
    }

    /** Places the order and returns what staff see for it in the order panel. */
    private JsonNode staffViewOf(MvcResult placed) throws Exception {
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        String orderId = json.readTree(placed.getResponse().getContentAsString()).at("/order/id").asText();
        return okStaff(get("/api/v1/orders/" + orderId));
    }

    @Test
    void aRidersDeliveryKeepsWhatTheCustomerAddedAndStaffSeeItInTheOrderPanel() throws Exception {
        JsonNode staffView = staffViewOf(checkout("DELIVERY_COURIER", "BODA", details()));

        JsonNode kept = staffView.get("deliveryDetails");
        assertThat(kept.get("estate").asText()).isEqualTo("Kilimani");
        assertThat(kept.get("street").asText()).isEqualTo("Argwings Kodhek Rd");
        assertThat(kept.get("building").asText()).isEqualTo("Yaya Towers");
        assertThat(kept.get("unit").asText()).isEqualTo("4th floor, door 12");
        assertThat(kept.get("directions").asText()).isEqualTo("Opposite the pharmacy, blue gate");
        assertThat(kept.get("instructions").asText()).isEqualTo("Call at the gate");
        assertThat(kept.get("receiverName").asText()).isEqualTo("Njeri Receptionist");
        assertThat(kept.get("timeWindow").asText()).isEqualTo("MORNING");
        assertThat(kept.get("courierOffice").isNull()).as("a rider has no courier office").isTrue();
        assertThat(kept.has("empty")).as("the helper that tells whether any detail was given is not part of the JSON").isFalse();
        assertThat(staffView.get("deliveryContactPhone").asText()).as("the number to ring is the existing field").isNotBlank();
    }

    @Test
    void aDropAtAStageKeepsOnlyWhatSuitsAStage() throws Exception {
        JsonNode kept = staffViewOf(checkout("DELIVERY_COURIER", "MATATU", details())).get("deliveryDetails");

        assertThat(kept.get("estate").asText()).isEqualTo("Kilimani");
        assertThat(kept.get("directions").asText()).isEqualTo("Opposite the pharmacy, blue gate");
        assertThat(kept.get("instructions").asText()).isEqualTo("Call at the gate");
        assertThat(kept.get("receiverName").asText()).isEqualTo("Njeri Receptionist");
        assertThat(kept.get("timeWindow").asText()).isEqualTo("MORNING");
        for (String doorstepOnly : new String[] {"street", "building", "unit", "courierOffice"}) {
            assertThat(kept.get(doorstepOnly).isNull()).as(doorstepOnly + " is not asked for at a stage").isTrue();
        }
    }

    @Test
    void aCourierParcelKeepsTheOfficeButNotAReceiverNameOrATimeOfDay() throws Exception {
        JsonNode kept = staffViewOf(checkout("DELIVERY_COURIER", "COURIER", details())).get("deliveryDetails");

        assertThat(kept.get("estate").asText()).isEqualTo("Kilimani");
        assertThat(kept.get("courierOffice").asText()).isEqualTo("Easy Coach Nakuru");
        assertThat(kept.get("directions").asText()).isEqualTo("Opposite the pharmacy, blue gate");
        assertThat(kept.get("instructions").asText()).isEqualTo("Call at the gate");
        for (String notForACourier : new String[] {"street", "building", "unit", "receiverName", "timeWindow"}) {
            assertThat(kept.get(notForACourier).isNull()).as(notForACourier + " is not asked for on a parcel").isTrue();
        }
    }

    @Test
    void aPickupKeepsNoDeliveryDetailsEvenIfSomeoneSendsThem() throws Exception {
        assertThat(staffViewOf(checkout("PICKUP", "BODA", details())).get("deliveryDetails").isNull()).isTrue();
    }

    @Test
    void anOrderThatGaveNoneOrOnlyBlanksStoresNone() throws Exception {
        assertThat(staffViewOf(checkout("DELIVERY_COURIER", "BODA", null)).get("deliveryDetails").isNull()).isTrue();

        ObjectNode blanks = json.createObjectNode().put("estate", "   ").put("directions", "\n \n").put("building", "");
        assertThat(staffViewOf(checkout("DELIVERY_COURIER", "BODA", blanks)).get("deliveryDetails").isNull()).isTrue();
    }

    @Test
    void messyTextIsTidiedBeforeItIsStored() throws Exception {
        ObjectNode messy = json.createObjectNode().put("building", "  Yaya \t Towers  ")
                .put("directions", "Blue gate\r\n\r\nsecond   line");

        JsonNode kept = staffViewOf(checkout("DELIVERY_COURIER", "BODA", messy)).get("deliveryDetails");

        assertThat(kept.get("building").asText()).isEqualTo("Yaya Towers");
        assertThat(kept.get("directions").asText()).isEqualTo("Blue gate\nsecond line");
    }

    @Test
    void aTooLongNoteOrAnUnknownTimeOfDayIsRefusedWithAClearReason() throws Exception {
        MvcResult tooLong = checkout("DELIVERY_COURIER", "BODA", json.createObjectNode().put("directions", "x".repeat(401)));
        assertThat(tooLong.getResponse().getStatus()).as(tooLong.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(tooLong.getResponse().getContentAsString()).contains("Directions must be 400 characters or fewer");

        MvcResult unknownTime = checkout("DELIVERY_COURIER", "BODA", json.createObjectNode().put("timeWindow", "MIDNIGHT"));
        assertThat(unknownTime.getResponse().getStatus()).as(unknownTime.getResponse().getContentAsString()).isEqualTo(400);
    }
}
