package com.nbh.backend.purchasing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.branch.entity.LocationType;
import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.branch.repository.StockLocationRepository;
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
 * The LPO lifecycle through the real API: create a draft, send it, receive it in two partial
 * deliveries, and confirm each receipt actually posted real stock (not just PO bookkeeping) —
 * plus the guardrails (can't cancel after receiving, can't over-receive a line).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseOrderIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private BranchRepository branchRepository;
    @Autowired private StockLocationRepository locationRepository;

    private String token;
    private UUID locationId;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);

        var branch = branchRepository.findByCode("BR-A").orElseThrow();
        locationId = locationRepository.findByBranchIdAndLocationType(branch.getId(), LocationType.BACKSTORE)
                .orElseThrow().getId();
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private int status(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String newSupplier() throws Exception {
        return ok(post("/api/v1/admin/suppliers"),
                "{\"name\":\"" + unique("Beauty Wholesale Ltd") + "\",\"phone\":\"0700000000\"}")
                .get("id").asText();
    }

    private String newVariant() throws Exception {
        String body = "{\"name\":\"" + unique("PO test product") + "\","
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"500\",\"vatExempt\":false}]}";
        return ok(post("/api/v1/products"), body).get("variants").get(0).get("id").asText();
    }

    @Test
    void aFreshOrderStartsAsDraftWithASequentialPoNumber() throws Exception {
        String supplierId = newSupplier();
        String variantId = newVariant();

        JsonNode po = ok(post("/api/v1/admin/purchase-orders"),
                "{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + variantId
                        + "\",\"qty\":\"50\",\"unitCost\":\"200\"}]}");

        assertThat(po.get("status").asText()).isEqualTo("DRAFT");
        assertThat(po.get("poNumber").asText()).matches("PO-\\d{8}-\\d{6}");
        assertThat(po.get("lines").get(0).get("qtyOrdered").decimalValue()).isEqualByComparingTo("50");
        assertThat(po.get("lines").get(0).get("qtyReceived").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void sendingThenReceivingInTwoPartsPostsRealStockAndTracksStatus() throws Exception {
        String supplierId = newSupplier();
        String variantId = newVariant();

        JsonNode po = ok(post("/api/v1/admin/purchase-orders"),
                "{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + variantId
                        + "\",\"qty\":\"100\",\"unitCost\":\"200\"}]}");
        String poId = po.get("id").asText();
        String lineId = po.get("lines").get(0).get("id").asText();

        // Can't receive a draft.
        assertThat(status(post("/api/v1/admin/purchase-orders/" + poId + "/receive"),
                "{\"locationId\":\"" + locationId + "\",\"lines\":[{\"poLineId\":\"" + lineId + "\",\"qty\":\"10\"}]}"))
                .isEqualTo(422);

        ok(post("/api/v1/admin/purchase-orders/" + poId + "/send"), null);

        // First partial receipt: 60 of 100.
        JsonNode afterFirst = ok(post("/api/v1/admin/purchase-orders/" + poId + "/receive"),
                "{\"locationId\":\"" + locationId + "\",\"lines\":[{\"poLineId\":\"" + lineId + "\",\"qty\":\"60\"}]}");
        assertThat(afterFirst.get("status").asText()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(afterFirst.get("lines").get(0).get("qtyReceived").decimalValue()).isEqualByComparingTo("60");

        JsonNode balance = ok(get("/api/v1/stock/balance?variantId=" + variantId + "&locationId=" + locationId), null);
        assertThat(balance.get(0).get("onHandQty").decimalValue()).isEqualByComparingTo("60");

        // Over-receiving what's left (100 - 60 = 40) is rejected.
        assertThat(status(post("/api/v1/admin/purchase-orders/" + poId + "/receive"),
                "{\"locationId\":\"" + locationId + "\",\"lines\":[{\"poLineId\":\"" + lineId + "\",\"qty\":\"41\"}]}"))
                .isEqualTo(422);

        // Second receipt finishes it off.
        JsonNode afterSecond = ok(post("/api/v1/admin/purchase-orders/" + poId + "/receive"),
                "{\"locationId\":\"" + locationId + "\",\"lines\":[{\"poLineId\":\"" + lineId + "\",\"qty\":\"40\"}]}");
        assertThat(afterSecond.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(afterSecond.get("lines").get(0).get("qtyReceived").decimalValue()).isEqualByComparingTo("100");

        JsonNode finalBalance = ok(get("/api/v1/stock/balance?variantId=" + variantId + "&locationId=" + locationId), null);
        assertThat(finalBalance.get(0).get("onHandQty").decimalValue()).isEqualByComparingTo("100");

        // Fully received — can't cancel.
        assertThat(status(post("/api/v1/admin/purchase-orders/" + poId + "/cancel"), null)).isEqualTo(422);
    }

    @Test
    void cancellingBeforeAnythingIsReceivedWorks() throws Exception {
        String supplierId = newSupplier();
        String variantId = newVariant();

        JsonNode po = ok(post("/api/v1/admin/purchase-orders"),
                "{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + variantId
                        + "\",\"qty\":\"10\",\"unitCost\":\"200\"}]}");

        JsonNode cancelled = ok(post("/api/v1/admin/purchase-orders/" + po.get("id").asText() + "/cancel"), null);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
    }
}
