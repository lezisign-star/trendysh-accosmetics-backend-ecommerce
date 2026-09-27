package com.nbh.backend.stock;

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

/** Phase 2 (2026-09-27): {@code POST /stock/write-offs} through the real API. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockWriteOffIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;

    private String token;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
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

    private String[] newVariantWithStock(String name, int qty) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = ok(post("/api/v1/products/draft"), body);
        String variantId = product.get("variants").get(0).get("id").asText();
        ok(put("/api/v1/products/" + product.get("id").asText() + "/variants/" + variantId + "/stock"),
                "{\"quantity\":" + qty + ",\"unitCost\":100}");
        return new String[]{product.get("id").asText(), variantId};
    }

    private java.math.BigDecimal shopOnHand(String variantId) throws Exception {
        JsonNode balances = ok(get("/api/v1/stock/balance?variantId=" + variantId), null);
        for (JsonNode b : balances) {
            if ("SHOP".equals(b.get("account").asText())) {
                return b.get("onHandQty").decimalValue();
            }
        }
        throw new AssertionError("no SHOP balance row for " + variantId);
    }

    @Test
    void writingOffTwoVariantsWithOneReasonPostsRealStockAndRecordsTheReasonOnTheDocument() throws Exception {
        String[] a = newVariantWithStock(unique("Damaged Widget A"), 20);
        String[] b = newVariantWithStock(unique("Damaged Widget B"), 15);

        JsonNode result = ok(post("/api/v1/stock/write-offs"), """
                {"lines":[{"variantId":"%s","qty":"5"},{"variantId":"%s","qty":"3"}],
                 "reasonCode":"DAMAGED","notes":"Storm damage on the top shelf"}
                """.formatted(a[1], b[1]));

        assertThat(result.get("documentNo").asText()).isNotBlank();
        assertThat(shopOnHand(a[1]).intValue()).isEqualTo(15);
        assertThat(shopOnHand(b[1]).intValue()).isEqualTo(12);

        JsonNode doc = ok(get("/api/v1/stock/documents/" + result.get("id").asText()), null);
        assertThat(doc.get("documentType").asText()).isEqualTo("WRITE_OFF");
        assertThat(doc.get("reasonCode").asText()).isEqualTo("DAMAGED");
        assertThat(doc.get("notes").asText()).isEqualTo("Storm damage on the top shelf");
        // 2 document lines, but 4 ledger entries — each line posts both its SHOP (negative)
        // and ADJUSTMENT (positive) side, per the double-entry design.
        assertThat(doc.get("lines").size()).isEqualTo(4);
    }

    @Test
    void writingOffMoreThanOnHandIsRejected() throws Exception {
        String[] a = newVariantWithStock(unique("Scarce Widget"), 3);

        int code = status(post("/api/v1/stock/write-offs"),
                """
                {"lines":[{"variantId":"%s","qty":"10"}],"reasonCode":"EXPIRED"}
                """.formatted(a[1]));

        // InsufficientStockException extends ConflictException in this codebase's exception
        // taxonomy (see GlobalExceptionHandler) — 409, not 422.
        assertThat(code).isEqualTo(409);
        assertThat(shopOnHand(a[1]).intValue()).isEqualTo(3);
    }

    @Test
    void aZeroOrNegativeLineQuantityIsRejected() throws Exception {
        String[] a = newVariantWithStock(unique("Zero Line Widget"), 10);

        int code = status(post("/api/v1/stock/write-offs"),
                """
                {"lines":[{"variantId":"%s","qty":"0"}],"reasonCode":"OTHER"}
                """.formatted(a[1]));

        assertThat(code).isEqualTo(400);
    }
}
