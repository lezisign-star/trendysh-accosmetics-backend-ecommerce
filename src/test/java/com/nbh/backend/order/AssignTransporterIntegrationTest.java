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
 * TumaBoda/matatu/courier integration, backend half (2026-09-28): who was actually
 * assigned to carry a DELIVERY_COURIER order, and the price agreed by phone, is now
 * recorded rather than living only in a staff member's memory of the call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssignTransporterIntegrationTest {

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

    private int statusStaff(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String newVariantWithStock(String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return variantId;
    }

    /** Places a real order via the public checkout flow, then confirms payment as staff, landing it on PAID. */
    private String[] paidOrder(String fulfilmentType, String deliveryMethod) throws Exception {
        String variantId = newVariantWithStock("Transporter Test " + UUID.randomUUID().toString().substring(0, 8));
        String sessionId = UUID.randomUUID().toString();
        MvcResult cartResult = mvc.perform(post("/api/v1/cart/items")
                        .header("X-Cart-Session", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variantId + "\",\"qty\":1}"))
                .andReturn();
        assertThat(cartResult.getResponse().getStatus()).isEqualTo(200);

        String deliveryFields = deliveryMethod == null ? ""
                : ",\"deliveryAddress\":\"Kilimani, Nairobi\",\"deliveryMethod\":\"" + deliveryMethod + "\"";
        String checkoutBody = """
                {"customerName":"Test Customer","guestEmail":"%s",
                 "guestPhone":"%s","fulfilmentType":"%s"%s}
                """.formatted(TestContacts.email(), TestContacts.phone(), fulfilmentType, deliveryFields);
        MvcResult checkoutResult = mvc.perform(post("/api/v1/checkout")
                        .header("X-Cart-Session", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody))
                .andReturn();
        assertThat(checkoutResult.getResponse().getStatus())
                .as(checkoutResult.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode checkout = json.readTree(checkoutResult.getResponse().getContentAsString());
        String orderId = checkout.get("order").get("id").asText();
        String paymentId = checkout.get("paymentRecordId").asText();

        // providerReference is unique across payment_record — a real M-Pesa/bank code is never reused.
        String reference = "TESTCONFIRM" + UUID.randomUUID().toString().substring(0, 8);
        okStaff(post("/api/v1/payments/" + paymentId + "/confirm-manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + reference + "\",\"method\":\"MPESA\"}"));

        return new String[]{orderId};
    }

    @Test
    void assigningATransporterPersistsAndIsVisibleOnTheOrder() throws Exception {
        String orderId = paidOrder("DELIVERY_COURIER", "BODA")[0];

        okStaff(post("/api/v1/orders/" + orderId + "/assign-transporter")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transporterName\":\"John Boda\",\"transporterPhone\":\"0733111222\",\"agreedPrice\":250}"));

        JsonNode detail = okStaff(get("/api/v1/orders/" + orderId));
        assertThat(detail.get("transporterName").asText()).isEqualTo("John Boda");
        assertThat(detail.get("transporterPhone").asText()).isEqualTo("254733111222");
        assertThat(detail.get("agreedDeliveryPrice").asDouble()).isEqualTo(250.0);
    }

    @Test
    void dispatchingACourierOrderWithoutATransporterIsRejected() throws Exception {
        String orderId = paidOrder("DELIVERY_COURIER", "MATATU")[0];

        assertThat(statusStaff(post("/api/v1/orders/" + orderId + "/fulfil"))).isEqualTo(422);
    }

    @Test
    void dispatchingSucceedsOnceATransporterIsAssigned() throws Exception {
        String orderId = paidOrder("DELIVERY_COURIER", "COURIER")[0];

        okStaff(post("/api/v1/orders/" + orderId + "/assign-transporter")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transporterName\":\"Fargo Courier\",\"transporterPhone\":\"0733111222\",\"agreedPrice\":500}"));

        assertThat(statusStaff(post("/api/v1/orders/" + orderId + "/fulfil"))).isEqualTo(200);
    }

    /**
     * A matatu parcel desk or a sacco office may be a landline, so the rule is looser than a customer's mobile
     * number, but staff must be able to dial it: the 2026-10-06 QA saved the "phone number" "banana".
     */
    @Test
    void theTransportersPhoneMustBeSomethingStaffCanDialButMayBeALandline() throws Exception {
        String orderId = paidOrder("DELIVERY_COURIER", "MATATU")[0];
        String url = "/api/v1/orders/" + orderId + "/assign-transporter";

        for (String bad : new String[] {"banana", "12345", "call me", "07;DROP"}) {
            assertThat(statusStaff(post(url).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transporterName\":\"Easy Coach\",\"transporterPhone\":\"" + bad + "\",\"agreedPrice\":300}")))
                    .as("phone '" + bad + "'").isEqualTo(400);
        }

        okStaff(post(url).contentType(MediaType.APPLICATION_JSON)
                .content("{\"transporterName\":\"Easy Coach parcel desk\",\"transporterPhone\":\"020 222 3344\",\"agreedPrice\":300}"));
        assertThat(okStaff(get("/api/v1/orders/" + orderId)).get("transporterPhone").asText()).isEqualTo("254202223344");

        okStaff(post(url).contentType(MediaType.APPLICATION_JSON)
                .content("{\"transporterName\":\"Boda Joe\",\"transporterPhone\":\"+254 733 111 222\",\"agreedPrice\":300}"));
        assertThat(okStaff(get("/api/v1/orders/" + orderId)).get("transporterPhone").asText()).isEqualTo("254733111222");
    }

    /**
     * 2026-10-10: the shop may pick the sacco for the shopper and tell them. The stage and receipt number are recorded,
     * can still be added after the parcel was dispatched, and changing them clears the "informed" mark.
     */
    @Test
    void theHandoffCanBeRecordedAfterDispatchAndChangingItAsksForTheShopperToBeToldAgain() throws Exception {
        String orderId = paidOrder("DELIVERY_COURIER", "MATATU")[0];
        String handoff = "/api/v1/orders/" + orderId + "/handoff";
        String informed = "/api/v1/orders/" + orderId + "/customer-informed";

        // Nothing to record until a transporter is assigned.
        assertThat(statusStaff(post(handoff).contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"Tom Mboya St\"}")))
                .isEqualTo(422);

        okStaff(post("/api/v1/orders/" + orderId + "/assign-transporter").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transporterName\":\"Nicco\",\"transporterPhone\":\"0733111222\",\"agreedPrice\":300}"));
        okStaff(post("/api/v1/orders/" + orderId + "/fulfil"));

        JsonNode saved = okStaff(post(handoff).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\" Tom Mboya St \",\"reference\":\"WB-1042\",\"note\":\"Ask for the green parcel\"}"));
        assertThat(saved.get("handoffStage").asText()).isEqualTo("Tom Mboya St");
        assertThat(saved.get("handoffReference").asText()).isEqualTo("WB-1042");
        assertThat(saved.get("customerInformedAt").isNull()).isTrue();

        JsonNode told = okStaff(post(informed));
        assertThat(told.get("customerInformedAt").isNull()).isFalse();

        // Saving the same details again does not undo it; a changed receipt number does.
        JsonNode same = okStaff(post(handoff).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"Tom Mboya St\",\"reference\":\"WB-1042\",\"note\":\"Ask for the green parcel\"}"));
        assertThat(same.get("customerInformedAt").isNull()).isFalse();
        JsonNode changed = okStaff(post(handoff).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"Tom Mboya St\",\"reference\":\"WB-1043\"}"));
        assertThat(changed.get("customerInformedAt").isNull()).isTrue();
        assertThat(changed.get("handoffNote").isNull()).isTrue();
    }

    @Test
    void aPickupOrderHasNoHandoff() throws Exception {
        String orderId = paidOrder("PICKUP", null)[0];

        assertThat(statusStaff(post("/api/v1/orders/" + orderId + "/customer-informed"))).isEqualTo(422);
    }

    @Test
    void assigningATransporterToAPickupOrderIsRejected() throws Exception {
        String orderId = paidOrder("PICKUP", null)[0];

        int code = statusStaff(post("/api/v1/orders/" + orderId + "/assign-transporter")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transporterName\":\"John Boda\",\"transporterPhone\":\"0733111222\",\"agreedPrice\":250}"));

        assertThat(code).isEqualTo(422);
    }
}
