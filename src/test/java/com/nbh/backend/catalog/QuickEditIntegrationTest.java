package com.nbh.backend.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * The quick-edit table ({@code GET /admin/quick-edit/rows}, {@code PUT /admin/quick-edit/variants/{id}}): one row per
 * option with its price, wholesale tier, reorder level and shelf count, a row saved whole or not at all, and the rules of
 * each number's own screen still applying.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuickEditIntegrationTest {

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

    private MvcResult call(MockHttpServletRequestBuilder request, String body, String as) throws Exception {
        request.header("Authorization", "Bearer " + as);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = call(request, body, token);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String refused(MockHttpServletRequestBuilder request, String body, int status) throws Exception {
        MvcResult result = call(request, body, token);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return result.getResponse().getContentAsString();
    }

    private static String w() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode newProduct(String name, String categoryId, String... prices) throws Exception {
        StringBuilder variants = new StringBuilder();
        for (int i = 0; i < prices.length; i++) {
            if (i > 0) variants.append(',');
            variants.append("{\"variantName\":\"").append(name).append(" – Shade ").append(i + 1)
                    .append("\",\"sellingPrice\":\"").append(prices[i]).append("\",\"isActive\":true}");
        }
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[" + (categoryId == null ? "" : "\"" + categoryId + "\"")
                + "],\"published\":false,\"variants\":[" + variants + "]}";
        return ok(post("/api/v1/products/draft"), body);
    }

    private JsonNode rows(String query) throws Exception {
        return ok(get("/api/v1/admin/quick-edit/rows?" + query), null).get("content");
    }

    private JsonNode rowOf(String variantId, String search) throws Exception {
        for (JsonNode row : rows("q=" + search + "&size=200")) {
            if (row.get("variantId").asText().equals(variantId)) return row;
        }
        throw new AssertionError("no row for " + variantId);
    }

    private static String save(String variantId) {
        return "/api/v1/admin/quick-edit/variants/" + variantId;
    }

    @Test
    void listsOneRowPerOptionGroupedByProductAndFilteredBySearchAndCategory() throws Exception {
        String w = w();
        String category = ok(post("/api/v1/categories"), "{\"name\":\"Quick " + w + "\"}").get("id").asText();
        String sub = ok(post("/api/v1/categories"), "{\"name\":\"Quick sub " + w + "\",\"parentId\":\"" + category + "\"}")
                .get("id").asText();
        newProduct("Alpha Lip " + w, sub, "500", "550");
        newProduct("Beta Brow " + w, null, "300");

        JsonNode found = rows("q=" + w);
        assertThat(found).hasSize(3);
        assertThat(found.get(0).get("productName").asText()).isEqualTo("Alpha Lip " + w);
        assertThat(found.get(0).get("sellingPrice").decimalValue()).isEqualByComparingTo("500");
        assertThat(found.get(1).get("sellingPrice").decimalValue()).isEqualByComparingTo("550");
        assertThat(found.get(0).get("categoryName").asText()).isEqualTo("Quick sub " + w);
        assertThat(found.get(0).get("onHandQty").decimalValue()).isEqualByComparingTo("0");
        assertThat(found.get(2).get("productName").asText()).isEqualTo("Beta Brow " + w);

        assertThat(rows("q=" + w + "&categoryId=" + category)).as("a parent category takes in its subcategories").hasSize(2);
        assertThat(rows("q=" + w + "&categoryId=" + sub)).hasSize(2);
    }

    @Test
    void savesPriceWholesaleReorderLevelAndCountTogether() throws Exception {
        String w = w();
        String variantId = newProduct("Gamma Gloss " + w, null, "1000").get("variants").get(0).get("id").asText();

        JsonNode result = ok(put(save(variantId)), "{\"sellingPrice\":{\"from\":1000,\"to\":900},"
                + "\"wholesale\":{\"fromPrice\":null,\"fromMinQty\":null,\"toPrice\":700,\"toMinQty\":6},"
                + "\"reorderLevel\":{\"from\":null,\"to\":4},\"onHand\":12}");

        JsonNode row = result.get("row");
        assertThat(row.get("sellingPrice").decimalValue()).isEqualByComparingTo("900");
        assertThat(row.get("wholesalePrice").decimalValue()).isEqualByComparingTo("700");
        assertThat(row.get("wholesaleMinQty").asInt()).isEqualTo(6);
        assertThat(row.get("reorderLevel").decimalValue()).isEqualByComparingTo("4");
        assertThat(row.get("onHandQty").decimalValue()).isEqualByComparingTo("12");
        assertThat(result.get("stockDocumentNo").asText()).as("the count went through the stock ledger").isNotBlank();
        assertThat(result.get("warnings")).isEmpty();

        JsonNode reread = rowOf(variantId, w);
        assertThat(reread.get("sellingPrice").decimalValue()).isEqualByComparingTo("900");
        assertThat(reread.get("onHandQty").decimalValue()).isEqualByComparingTo("12");

        JsonNode cleared = ok(put(save(variantId)),
                "{\"wholesale\":{\"fromPrice\":700,\"fromMinQty\":6,\"toPrice\":null,\"toMinQty\":null},"
                        + "\"reorderLevel\":{\"from\":4,\"to\":null}}").get("row");
        assertThat(cleared.get("wholesalePrice").isNull()).isTrue();
        assertThat(cleared.get("wholesaleMinQty").isNull()).isTrue();
        assertThat(cleared.get("reorderLevel").isNull()).isTrue();
    }

    @Test
    void aRowSomeoneElseChangedIsRefusedWholeAndNothingIsWritten() throws Exception {
        String w = w();
        String variantId = newProduct("Delta Liner " + w, null, "400").get("variants").get(0).get("id").asText();

        String body = refused(put(save(variantId)),
                "{\"sellingPrice\":{\"from\":450,\"to\":500},\"onHand\":9}", 409);
        assertThat(body).contains("Someone changed the price");

        JsonNode row = rowOf(variantId, w);
        assertThat(row.get("sellingPrice").decimalValue()).isEqualByComparingTo("400");
        assertThat(row.get("onHandQty").decimalValue()).as("the count on the same row was not posted").isEqualByComparingTo("0");
    }

    @Test
    void aBadNumberOnTheRowUndoesTheRestOfIt() throws Exception {
        String w = w();
        String variantId = newProduct("Echo Blush " + w, null, "800").get("variants").get(0).get("id").asText();

        refused(put(save(variantId)), "{\"sellingPrice\":{\"from\":800,\"to\":600},"
                + "\"wholesale\":{\"fromPrice\":null,\"fromMinQty\":null,\"toPrice\":650,\"toMinQty\":5},\"onHand\":3}", 400);
        refused(put(save(variantId)),
                "{\"wholesale\":{\"fromPrice\":null,\"fromMinQty\":null,\"toPrice\":650,\"toMinQty\":null}}", 400);

        JsonNode row = rowOf(variantId, w);
        assertThat(row.get("sellingPrice").decimalValue()).isEqualByComparingTo("800");
        assertThat(row.get("wholesalePrice").isNull()).isTrue();
        assertThat(row.get("onHandQty").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void aPriceBelowTheWholesalePriceClearsTheTierAndSaysSo() throws Exception {
        String w = w();
        String variantId = newProduct("Foxtrot Kohl " + w, null, "1000").get("variants").get(0).get("id").asText();
        ok(put(save(variantId)), "{\"wholesale\":{\"fromPrice\":null,\"fromMinQty\":null,\"toPrice\":700,\"toMinQty\":10}}");

        JsonNode result = ok(put(save(variantId)), "{\"sellingPrice\":{\"from\":1000,\"to\":650}}");
        assertThat(result.get("row").get("wholesalePrice").isNull()).isTrue();
        assertThat(result.get("warnings").toString()).contains("wholesale tier was cleared");
    }

    @Test
    void anOptionOfARangeWithOneSharedPriceIsPricedOnTheRange() throws Exception {
        String w = w();
        JsonNode product = newProduct("Golf Gel " + w, null, "350", "350");
        String id = product.get("id").asText();
        ok(put("/api/v1/products/" + id + "/range"), "{\"name\":\"Golf Gel " + w + "\",\"description\":null,"
                + "\"sharedPrice\":350,\"sharedSize\":null,\"categoryIds\":[],\"priceVaries\":false,\"sizeVaries\":false,"
                + "\"descriptionVaries\":false,\"categoryVaries\":false}");
        String variantId = product.get("variants").get(0).get("id").asText();

        assertThat(rowOf(variantId, w).get("sharedPrice").decimalValue()).isEqualByComparingTo("350");
        assertThat(refused(put(save(variantId)), "{\"sellingPrice\":{\"from\":350,\"to\":400}}", 422))
                .contains("costs the same");
        ok(put(save(variantId)), "{\"onHand\":5}");
    }

    @Test
    void aViewerCanNeitherListNorSave() throws Exception {
        String w = w();
        String variantId = newProduct("Hotel Tint " + w, null, "200").get("variants").get(0).get("id").asText();
        StaffUser viewer = new StaffUser();
        viewer.setEmail("viewer-" + w + "@example.com");
        viewer.setPassword(passwordEncoder.encode("irrelevant-password"));
        viewer.setFirstName("Read");
        viewer.setLastName("Only");
        viewer.setStaffRole(staffRoles.findByName("VIEWER").orElseThrow());
        String viewerToken = jwtService.generateAccessToken(staffUsers.save(viewer));

        assertThat(call(get("/api/v1/admin/quick-edit/rows"), null, viewerToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(put(save(variantId)), "{\"onHand\":5}", viewerToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(rowOf(variantId, w).get("onHandQty").decimalValue()).isEqualByComparingTo("0");
    }
}
