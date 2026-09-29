package com.nbh.backend.stock.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductSaleEvent;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductSaleEventRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.common.event.LowStockThresholdCrossedEvent;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The stock-alert emails (instant low-stock, morning digest), who receives them, and the Settings
 * endpoints that control it. Email is mocked; products get unique names so the shared test database
 * can hold anything else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockAlertsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StockAlertService alertService;
    @Autowired private StockAlertSettingsService settingsService;
    @Autowired private VariantStockService stockService;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private ProductSaleEventRepository saleEvents;
    @Autowired private CustomerOrderRepository orders;
    @MockitoBean private EmailSender emailSender;

    private StaffUser admin;
    private String token;

    @BeforeEach
    void setUp() {
        admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
        settingsService.update(7, 3, true);
        settingsService.markDigestSent(LocalDate.now().minusDays(1));
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private StaffUser staff(String role, boolean receives) {
        StaffUser user = new StaffUser();
        user.setEmail(role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode("irrelevant-password"));
        user.setFirstName("Test");
        user.setLastName(role);
        user.setStaffRole(staffRoles.findByName(role).orElseThrow());
        user.setReceivesStockAlerts(receives);
        return staffUsers.save(user);
    }

    private UUID[] newProduct(String name, int stock) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":true,"
                + "\"variants\":[{\"variantName\":\"Only\",\"sellingPrice\":\"500\",\"isActive\":true}]}";
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
        order.setOrderNo("AL-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
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

    private String sku(UUID variantId) {
        return variantRepository.findById(variantId).orElseThrow().getSku();
    }

    @Test
    void aLowStockAlertGoesToOptedInStaffOnlyAndNamesTheProduct() throws Exception {
        String name = "Alert Serum " + UUID.randomUUID().toString().substring(0, 8);
        UUID[] ids = newProduct(name, 4);
        StaffUser in = staff("MANAGER", true);
        StaffUser out = staff("MANAGER", false);

        alertService.sendLowStockAlert(new LowStockThresholdCrossedEvent(ids[1], sku(ids[1]),
                BigDecimal.valueOf(6), BigDecimal.valueOf(4), BigDecimal.valueOf(5)));

        verify(emailSender).send(eq(in.getEmail()), contains(name), contains("Now in stock: 4"), any());
        verify(emailSender, never()).send(eq(out.getEmail()), any(), any(), any());
    }

    @Test
    void aDisabledAccountGetsNothing() throws Exception {
        UUID[] ids = newProduct("Alert Disabled " + UUID.randomUUID().toString().substring(0, 8), 4);
        StaffUser gone = staff("MANAGER", true);
        gone.setEnabled(false);
        staffUsers.save(gone);

        alertService.sendLowStockAlert(new LowStockThresholdCrossedEvent(ids[1], sku(ids[1]),
                BigDecimal.valueOf(6), BigDecimal.valueOf(4), BigDecimal.valueOf(5)));

        verify(emailSender, never()).send(eq(gone.getEmail()), any(), any(), any());
    }

    @Test
    void demoProductsNeverTriggerAnAlert() throws Exception {
        UUID[] ids = newProduct("Alert Demo " + UUID.randomUUID().toString().substring(0, 8), 4);
        Product product = productRepository.findById(ids[0]).orElseThrow();
        product.setMockData(true);
        productRepository.save(product);
        StaffUser in = staff("MANAGER", true);

        try {
            alertService.sendLowStockAlert(new LowStockThresholdCrossedEvent(ids[1], sku(ids[1]),
                    BigDecimal.valueOf(6), BigDecimal.valueOf(4), BigDecimal.valueOf(5)));

            verify(emailSender, never()).send(eq(in.getEmail()), any(), any(), any());
        } finally {
            // A leftover demo-tagged product would make the demo-data toggle think demo data already exists.
            product.setMockData(false);
            productRepository.save(product);
        }
    }

    @Test
    void aRealSaleThatCrossesTheReorderLevelEmailsStaffWithoutBlockingTheSale() throws Exception {
        String name = "Alert Live " + UUID.randomUUID().toString().substring(0, 8);
        UUID[] ids = newProduct(name, 12);
        StaffUser in = staff("MANAGER", true);
        ok(put("/api/v1/stock/insights/variants/" + ids[1] + "/reorder-level"), "{\"reorderLevel\":10}");

        // 12 -> 8 crosses the level of 10: the movement commits, then the email goes out on its own thread.
        stockService.setOnHand(ids[0], ids[1], BigDecimal.valueOf(8), null, "sold", admin);

        verify(emailSender, timeout(5000)).send(eq(in.getEmail()), contains(name), any(), any());
    }

    @Test
    void theDigestListsWhatIsRunningOutAndGoesOutOnlyOncePerDay() throws Exception {
        String name = "Alert Digest " + UUID.randomUUID().toString().substring(0, 8);
        UUID[] ids = newProduct(name, 0);   // sells but none left: first in the list however much else the shared DB holds
        sell(ids[0], ids[1], 30, 4);
        StaffUser in = staff("STOCK_CONTROLLER", true);
        StaffUser out = staff("STOCK_CONTROLLER", false);

        int first = alertService.sendDigestIfDue(LocalDate.now());
        int second = alertService.sendDigestIfDue(LocalDate.now());

        assertThat(first).isGreaterThanOrEqualTo(1);
        assertThat(second).as("already sent today").isZero();
        verify(emailSender).send(eq(in.getEmail()), contains("attention"), contains(name), contains(name));
        verify(emailSender, never()).send(eq(out.getEmail()), any(), any(), any());
    }

    @Test
    void theDigestCanBeSwitchedOff() throws Exception {
        UUID[] ids = newProduct("Alert Off " + UUID.randomUUID().toString().substring(0, 8), 6);
        sell(ids[0], ids[1], 30, 4);
        StaffUser in = staff("MANAGER", true);
        settingsService.update(7, 3, false);

        assertThat(alertService.sendDigestIfDue(LocalDate.now())).isZero();

        verify(emailSender, never()).send(eq(in.getEmail()), any(), any(), any());
    }

    @Test
    void settingsCanBeReadAndChangedByAnAdminAndRecipientsToggled() throws Exception {
        StaffUser member = staff("CASHIER", true);

        JsonNode before = ok(get("/api/v1/admin/stock-alerts"), null);
        assertThat(before.get("leadTimeDays").asInt()).isEqualTo(7);
        assertThat(before.toString()).contains(member.getEmail());

        JsonNode updated = ok(put("/api/v1/admin/stock-alerts/settings"),
                "{\"leadTimeDays\":14,\"safetyDays\":5,\"digestEnabled\":false}");
        assertThat(updated.get("leadTimeDays").asInt()).isEqualTo(14);
        assertThat(updated.get("safetyDays").asInt()).isEqualTo(5);
        assertThat(updated.get("digestEnabled").asBoolean()).isFalse();

        JsonNode toggled = ok(patch("/api/v1/admin/stock-alerts/recipients/" + member.getId()), "{\"receives\":false}");
        for (JsonNode r : toggled.get("recipients")) {
            if (member.getId().toString().equals(r.get("staffId").asText())) {
                assertThat(r.get("receives").asBoolean()).isFalse();
            }
        }
        assertThat(staffUsers.findById(member.getId()).orElseThrow().isReceivesStockAlerts()).isFalse();
    }

    @Test
    void outOfRangeSettingsAreRejected() throws Exception {
        MvcResult result = mvc.perform(put("/api/v1/admin/stock-alerts/settings")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadTimeDays\":500,\"safetyDays\":3,\"digestEnabled\":true}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void onlyAnAdminMayChangeSettingsOrRecipients() throws Exception {
        String manager = jwtService.generateAccessToken(staff("MANAGER", true));
        StaffUser member = staff("CASHIER", true);

        mvc.perform(get("/api/v1/admin/stock-alerts").header("Authorization", "Bearer " + manager))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/stock-alerts/settings").header("Authorization", "Bearer " + manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leadTimeDays\":7,\"safetyDays\":3,\"digestEnabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/stock-alerts/recipients/" + member.getId()).header("Authorization", "Bearer " + manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receives\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTestDigestGoesOnlyToTheAdminWhoAskedForIt() throws Exception {
        JsonNode result = ok(post("/api/v1/admin/stock-alerts/test"), null);

        assertThat(result.get("sentTo").asText()).isEqualTo(admin.getEmail());
        verify(emailSender).send(eq(admin.getEmail()), any(), any(), any());
        verify(emailSender, never()).send(eq("nobody-else@example.com"), any(), any(), any());
    }
}
