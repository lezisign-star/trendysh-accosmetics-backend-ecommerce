package com.nbh.backend.stock.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductSaleEvent;
import com.nbh.backend.catalog.repository.ProductSaleEventRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.stock.service.VariantStockService;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Forecast tab's API and reorder levels, end to end: real product, real stock, real sales history.
 * Unique product names per run so it can share the database with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockInsightsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private VariantStockService stockService;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private ProductSaleEventRepository saleEvents;
    @Autowired private CustomerOrderRepository orders;
    @Autowired private StockAlertSettingsService settingsService;

    private StaffUser admin;
    private String token;

    @BeforeEach
    void setUp() {
        admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
        settingsService.update(7, 3, true);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body, String bearer) throws Exception {
        request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = call(request, body, token);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String tokenFor(String role) {
        StaffUser user = new StaffUser();
        user.setEmail(role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode("irrelevant-password"));
        user.setFirstName("Test");
        user.setLastName(role);
        user.setStaffRole(staffRoles.findByName(role).orElseThrow());
        return jwtService.generateAccessToken(staffUsers.save(user));
    }

    /** A published product with one variant priced 500, holding {@code stock} units. */
    private UUID[] newProduct(String name, boolean published, int stock) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":" + published
                + ",\"variants\":[{\"variantName\":\"Only\",\"sellingPrice\":\"500\",\"isActive\":true}]}";
        JsonNode product = ok(post("/api/v1/products/draft"), body);
        UUID productId = UUID.fromString(product.get("id").asText());
        UUID variantId = UUID.fromString(product.get("variants").get(0).get("id").asText());
        if (stock > 0) {
            stockService.setOnHand(productId, variantId, BigDecimal.valueOf(stock), BigDecimal.valueOf(300), "test", admin);
        }
        return new UUID[]{productId, variantId};
    }

    private void sell(UUID productId, UUID variantId, int qty, int daysAgo) {
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("FC-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        order.setGuestEmail("shopper@example.com");
        order.setGuestPhone("254700000000");
        order.setFulfilmentType(FulfilmentType.PICKUP);
        order.setStatus(OrderStatus.DELIVERED);
        order.setSubtotal(BigDecimal.valueOf(500L * qty));
        order.setVatTotal(BigDecimal.ZERO);
        order.setGrandTotal(BigDecimal.valueOf(500L * qty));
        order = orders.save(order);

        ProductSaleEvent sale = new ProductSaleEvent();
        sale.setProduct(variantRepository.findById(variantId).orElseThrow().getProduct());
        sale.setVariant(variantRepository.findById(variantId).orElseThrow());
        sale.setOrder(order);
        sale.setQty(BigDecimal.valueOf(qty));
        sale.setOccurredAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS));
        saleEvents.save(sale);
    }

    private JsonNode rowFor(UUID variantId) throws Exception {
        for (JsonNode row : ok(get("/api/v1/stock/insights/forecast"), null).get("rows")) {
            if (variantId.toString().equals(row.get("variantId").asText())) return row;
        }
        return null;
    }

    @Test
    void forecastReportsDaysLeftAndSuggestedQuantitiesFromRealSales() throws Exception {
        String name = "Forecast Gel " + UUID.randomUUID().toString().substring(0, 8);
        UUID[] ids = newProduct(name, true, 12);
        // 60 units over the last month, 30 of them in the last week.
        sell(ids[0], ids[1], 15, 1);
        sell(ids[0], ids[1], 15, 3);
        sell(ids[0], ids[1], 15, 10);
        sell(ids[0], ids[1], 15, 20);

        JsonNode row = rowFor(ids[1]);

        assertThat(row).as("the variant is in the forecast").isNotNull();
        assertThat(row.get("productName").asText()).isEqualTo(name);
        assertThat(row.get("available").decimalValue()).isEqualByComparingTo("12");
        assertThat(row.get("soldLast30").decimalValue()).isEqualByComparingTo("60");
        assertThat(row.get("avgPerDay").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(row.get("daysOfCover").decimalValue()).isEqualByComparingTo("6.0");
        assertThat(row.get("status").asText()).isEqualTo("CRITICAL");
        assertThat(row.get("trend").asText()).isEqualTo("UP");
        assertThat(row.get("suggestedRestockQty").decimalValue()).isEqualByComparingTo("48");
        assertThat(row.get("suggestedReorderLevel").decimalValue()).isEqualByComparingTo("20");
        assertThat(row.get("reorderLevel").isNull()).isTrue();
    }

    @Test
    void anUnpublishedProductIsNotForecast() throws Exception {
        UUID[] ids = newProduct("Forecast Draft " + UUID.randomUUID().toString().substring(0, 8), false, 5);
        sell(ids[0], ids[1], 10, 2);

        assertThat(rowFor(ids[1])).isNull();
    }

    @Test
    void reorderLevelCanBeSetClearedAndAppliedFromTheSuggestion() throws Exception {
        UUID[] ids = newProduct("Forecast Level " + UUID.randomUUID().toString().substring(0, 8), true, 12);
        sell(ids[0], ids[1], 30, 2);
        sell(ids[0], ids[1], 30, 12);
        String url = "/api/v1/stock/insights/variants/" + ids[1] + "/reorder-level";

        ok(put(url), "{\"reorderLevel\":15}");
        JsonNode row = rowFor(ids[1]);
        assertThat(row.get("reorderLevel").decimalValue()).isEqualByComparingTo("15");
        assertThat(row.get("belowReorderLevel").asBoolean()).isTrue();   // 12 on the shelf, level 15

        ok(put(url), "{\"reorderLevel\":null}");
        assertThat(rowFor(ids[1]).get("reorderLevel").isNull()).isTrue();

        JsonNode applied = ok(post("/api/v1/stock/insights/reorder-levels/apply-suggested"),
                "{\"variantIds\":[\"" + ids[1] + "\"]}");
        assertThat(applied.get("updated").asInt()).isEqualTo(1);
        assertThat(rowFor(ids[1]).get("reorderLevel").decimalValue()).isEqualByComparingTo("20");   // 60 sold: 2/day x 10 days
    }

    @Test
    void aNegativeReorderLevelIsRejected() throws Exception {
        UUID[] ids = newProduct("Forecast Negative " + UUID.randomUUID().toString().substring(0, 8), true, 1);

        MvcResult result = call(put("/api/v1/stock/insights/variants/" + ids[1] + "/reorder-level"),
                "{\"reorderLevel\":-1}", token);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void stockAlreadyOnOrderIsNotSuggestedAgain() throws Exception {
        UUID[] ids = newProduct("Forecast Ordered " + UUID.randomUUID().toString().substring(0, 8), true, 12);
        sell(ids[0], ids[1], 60, 5);
        assertThat(rowFor(ids[1]).get("suggestedRestockQty").decimalValue()).isEqualByComparingTo("48");

        String supplierId = ok(post("/api/v1/admin/suppliers"),
                "{\"name\":\"Forecast Supplier " + UUID.randomUUID().toString().substring(0, 8) + "\",\"phone\":\"0700000000\"}")
                .get("id").asText();
        String poId = ok(post("/api/v1/admin/purchase-orders"),
                "{\"supplierId\":\"" + supplierId + "\",\"lines\":[{\"variantId\":\"" + ids[1]
                        + "\",\"qty\":\"30\",\"unitCost\":\"300\"}]}").get("id").asText();

        // A draft isn't on its way yet.
        assertThat(rowFor(ids[1]).get("onOrder").decimalValue()).isEqualByComparingTo("0");

        ok(post("/api/v1/admin/purchase-orders/" + poId + "/send"), null);

        JsonNode row = rowFor(ids[1]);
        assertThat(row.get("onOrder").decimalValue()).isEqualByComparingTo("30");
        assertThat(row.get("suggestedRestockQty").decimalValue()).isEqualByComparingTo("18");   // 60 - 12 on shelf - 30 coming
    }

    @Test
    void aCashierCanSeeTheForecastButCannotChangeReorderLevels() throws Exception {
        String cashier = tokenFor("CASHIER");
        UUID[] ids = newProduct("Forecast Cashier " + UUID.randomUUID().toString().substring(0, 8), true, 3);

        assertThat(call(get("/api/v1/stock/insights/forecast"), null, cashier).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get("/api/v1/stock/insights/summary"), null, cashier).getResponse().getStatus()).isEqualTo(200);
        mvc.perform(put("/api/v1/stock/insights/variants/" + ids[1] + "/reorder-level")
                        .header("Authorization", "Bearer " + cashier)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reorderLevel\":5}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/stock/insights/forecast")).andExpect(status().isForbidden());
    }

    @Test
    void aMissingRequiredParameterIsTheCallersMistakeNotAServerError() throws Exception {
        // GET /stock/balance needs ?variantId=. Leaving it out used to surface as a 500 "unexpected error".
        MvcResult missing = call(get("/api/v1/stock/balance"), null, token);
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(missing.getResponse().getContentAsString()).contains("variantId");
    }

    @Test
    void summaryCountsWhatNeedsAttention() throws Exception {
        UUID[] ids = newProduct("Forecast Summary " + UUID.randomUUID().toString().substring(0, 8), true, 4);
        sell(ids[0], ids[1], 30, 4);

        JsonNode summary = ok(get("/api/v1/stock/insights/summary"), null);

        assertThat(summary.get("critical").asInt() + summary.get("outNow").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(summary.get("needsAttention").asInt()).isGreaterThanOrEqualTo(1);
    }
}
