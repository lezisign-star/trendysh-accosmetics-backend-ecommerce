package com.nbh.backend.kits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.repository.StockReservationRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A kit end to end: admin creates one from real components, it shows up wherever a normal variant
 * does, a customer adds it to cart and checks out as ONE line at the package price, staff see the
 * real components nested under it, fulfilling it moves stock only on those real components (never
 * the kit's own stockless SKU), and cancelling releases every one of those holds. See KitService's
 * javadoc for the "a kit IS a Product+ProductVariant" architecture this all rests on, and the plan
 * this was built from (one-click-add-kit-to-glistening-pinwheel.md) for the full design record.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KitIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private StockReservationRepository reservationRepository;

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

    private static String uniqueSlug(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    /** Public endpoints (cart/checkout) — scoped by session header, never the admin token. */
    private MvcResult cartRaw(MockHttpServletRequestBuilder request, String body, String sessionId) throws Exception {
        request.header("X-Cart-Session", sessionId);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode cartOk(MockHttpServletRequestBuilder request, String body, String sessionId) throws Exception {
        MvcResult result = cartRaw(request, body, sessionId);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private BigDecimal shopOnHand(String variantId) throws Exception {
        JsonNode balances = ok(get("/api/v1/stock/balance?variantId=" + variantId), null);
        for (JsonNode b : balances) {
            if ("SHOP".equals(b.get("account").asText())) {
                return b.get("onHandQty").decimalValue();
            }
        }
        return null; // no balance row at all — exactly what a kit's own SKU should show
    }

    private String newSupplier() throws Exception {
        return ok(post("/api/v1/admin/suppliers"),
                "{\"name\":\"" + unique("Kit Component Supplier") + "\",\"phone\":\"0700000000\"}")
                .get("id").asText();
    }

    private String newComponentVariant(BigDecimal sellingPrice) throws Exception {
        String body = "{\"name\":\"" + unique("Kit component") + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"" + sellingPrice + "\",\"isActive\":true}]}";
        return ok(post("/api/v1/products/draft"), body).get("variants").get(0).get("id").asText();
    }

    /**
     * Gives each variant a real StockBalance via a full PO receive — the same path
     * {@code PurchaseOrderIntegrationTest} uses. Returns the received purchase order so a caller
     * can pull a component's real SKU off it when a test needs one (see the starved-stock test).
     */
    private JsonNode receiveStock(Map<String, BigDecimal> qtyByVariantId) throws Exception {
        String supplierId = newSupplier();
        StringBuilder lines = new StringBuilder();
        for (var e : qtyByVariantId.entrySet()) {
            if (!lines.isEmpty()) lines.append(",");
            lines.append("{\"variantId\":\"").append(e.getKey()).append("\",\"qty\":\"").append(e.getValue())
                    .append("\",\"unitCost\":\"100\"}");
        }
        JsonNode po = ok(post("/api/v1/admin/purchase-orders"),
                "{\"supplierId\":\"" + supplierId + "\",\"lines\":[" + lines + "]}");
        String poId = po.get("id").asText();
        ok(post("/api/v1/admin/purchase-orders/" + poId + "/send"), null);

        StringBuilder receiveLines = new StringBuilder();
        for (JsonNode line : po.get("lines")) {
            if (!receiveLines.isEmpty()) receiveLines.append(",");
            String variantId = line.get("variantId").asText();
            receiveLines.append("{\"poLineId\":\"").append(line.get("id").asText())
                    .append("\",\"qty\":\"").append(qtyByVariantId.get(variantId)).append("\"}");
        }
        return ok(post("/api/v1/admin/purchase-orders/" + poId + "/receive"), "{\"lines\":[" + receiveLines + "]}");
    }

    private String newCategory() {
        Category c = new Category();
        c.setSlug(uniqueSlug("kit-test-category"));
        c.setName(unique("Kit Test Category"));
        return categoryRepository.save(c).getId().toString();
    }

    private String newProfession() throws Exception {
        return ok(post("/api/v1/admin/professions"),
                "{\"label\":\"" + unique("Nail Tech") + "\",\"slug\":\"" + uniqueSlug("nail-tech") + "\"}")
                .get("id").asText();
    }

    private JsonNode createKit(String categoryId, String professionId, String description,
                                List<String> variantIds, List<String> qtys, String finalPrice) throws Exception {
        StringBuilder components = new StringBuilder();
        for (int i = 0; i < variantIds.size(); i++) {
            if (!components.isEmpty()) components.append(",");
            components.append("{\"variantId\":\"").append(variantIds.get(i))
                    .append("\",\"qty\":\"").append(qtys.get(i)).append("\"}");
        }
        String body = "{\"name\":\"" + unique("Test Kit")
                + "\",\"professionId\":\"" + professionId
                + "\",\"categoryId\":\"" + categoryId
                + "\",\"description\":\"" + description
                + "\",\"finalPrice\":\"" + finalPrice
                + "\",\"components\":[" + components + "]}";
        return ok(post("/api/v1/admin/kits"), body);
    }

    /** A fresh phone per call — CheckoutService caps open orders per contact, and every test in
     *  this class reusing the same number would trip that limit across repeated local runs. */
    private String checkoutRequestBody(String email) {
        String phone = "2547" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
        return "{\"customerName\":\"Test Customer\",\"guestEmail\":\"" + email
                + "\",\"guestPhone\":\"" + phone + "\",\"fulfilmentType\":\"PICKUP\"}";
    }

    @Test
    void kitChecksOutAsOneLineAndFulfilsOnlyItsRealComponents() throws Exception {
        String categoryId = newCategory();
        String variantA = newComponentVariant(new BigDecimal("500"));
        String variantB = newComponentVariant(new BigDecimal("300"));
        String variantC = newComponentVariant(new BigDecimal("200"));
        receiveStock(Map.of(variantA, new BigDecimal("10"), variantB, new BigDecimal("10"), variantC, new BigDecimal("10")));

        JsonNode kit = createKit(categoryId, newProfession(), "A full starter set for a new Nail Tech",
                List.of(variantA, variantB, variantC), List.of("2", "1", "3"), "1500");
        String kitVariantId = kit.get("variantId").asText();
        assertThat(kit.get("sku").asText()).startsWith("KIT-");

        String sessionId = "kit-session-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), "{\"variantId\":\"" + kitVariantId + "\",\"qty\":1}", sessionId);

        JsonNode checkoutResult = cartOk(post("/api/v1/checkout"), checkoutRequestBody("kit-golden@example.com"), sessionId);
        String orderId = checkoutResult.get("order").get("id").asText();

        JsonNode detail = ok(get("/api/v1/orders/" + orderId), null);
        assertThat(detail.get("items")).hasSize(1);
        JsonNode kitLine = detail.get("items").get(0);
        assertThat(kitLine.get("lineTotal").decimalValue()).isEqualByComparingTo("1500");
        assertThat(kitLine.get("components")).hasSize(3);
        assertThat(detail.get("subtotal").decimalValue()).isEqualByComparingTo("1500");

        List<StockReservation> reservations = reservationRepository.findByReferenceTypeAndReferenceId(
                "CUSTOMER_ORDER", UUID.fromString(orderId));
        assertThat(reservations).hasSize(3);
        assertThat(reservations).allMatch(r -> r.getStatus() == StockReservation.ReservationStatus.ACTIVE);

        // A manual bank confirmation would flip this the same way — PENDING_PAYMENT -> PAID.
        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);

        ok(post("/api/v1/orders/" + orderId + "/fulfil"), null);

        assertThat(shopOnHand(variantA)).isEqualByComparingTo("8");
        assertThat(shopOnHand(variantB)).isEqualByComparingTo("9");
        assertThat(shopOnHand(variantC)).isEqualByComparingTo("7");
        assertThat(shopOnHand(kitVariantId)).isNull(); // the kit's own SKU never posts a stock movement
    }

    @Test
    void kitAppearsInGeneralSearchAndThePublicKitsEndpoint() throws Exception {
        String categoryId = newCategory();
        String variantA = newComponentVariant(new BigDecimal("400"));
        receiveStock(Map.of(variantA, new BigDecimal("5")));

        JsonNode kit = createKit(categoryId, newProfession(), "For a working Lash Tech",
                List.of(variantA), List.of("1"), "350");
        String kitName = kit.get("name").asText();

        MvcResult searchResult = mvc.perform(get("/api/v1/catalog/variants").param("q", kitName)).andReturn();
        assertThat(searchResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode searchBody = json.readTree(searchResult.getResponse().getContentAsString());
        boolean foundInSearch = false;
        for (JsonNode v : searchBody.get("content")) {
            if (kitName.equals(v.get("productName").asText())) {
                assertThat(v.get("inStock").asBoolean()).isTrue();
                foundInSearch = true;
            }
        }
        assertThat(foundInSearch).as("kit should appear in the general storefront search").isTrue();

        MvcResult kitsResult = mvc.perform(get("/api/v1/catalog/kits")).andReturn();
        assertThat(kitsResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode kitsBody = json.readTree(kitsResult.getResponse().getContentAsString());
        JsonNode match = null;
        for (JsonNode k : kitsBody) {
            if (kitName.equals(k.get("name").asText())) {
                match = k;
            }
        }
        assertThat(match).as("kit should appear in the public kits endpoint").isNotNull();
        assertThat(match.get("packagePrice").decimalValue()).isEqualByComparingTo("350");
        assertThat(match.get("sumOfComponents").decimalValue()).isEqualByComparingTo("400");
        assertThat(match.get("savings").decimalValue()).isEqualByComparingTo("50");
        assertThat(match.get("items")).hasSize(1);
    }

    @Test
    void addingAKitToCartSucceedsDespiteTheKitHavingNoStockBalanceOfItsOwn() throws Exception {
        // Regression test: CartService.checkCourtesyAvailability used to resolve a kit's own
        // availability to ZERO (no StockBalance row exists for a stockless kit SKU) and reject
        // every add-to-cart with "out of stock" — see ProductVariant#isKit's javadoc for the fix.
        String categoryId = newCategory();
        String variantA = newComponentVariant(new BigDecimal("250"));
        receiveStock(Map.of(variantA, new BigDecimal("5")));

        JsonNode kit = createKit(categoryId, newProfession(), "For a working Makeup Artist",
                List.of(variantA), List.of("1"), "200");
        String kitVariantId = kit.get("variantId").asText();

        String sessionId = "kit-cart-session-" + UUID.randomUUID();
        JsonNode cart = cartOk(post("/api/v1/cart/items"), "{\"variantId\":\"" + kitVariantId + "\",\"qty\":1}", sessionId);
        assertThat(cart.get("items")).hasSize(1);
    }

    @Test
    void aStarvedComponentFailsCheckoutNamingTheKitNotTheInternalSku() throws Exception {
        String categoryId = newCategory();
        String variantA = newComponentVariant(new BigDecimal("500"));
        // Only 1 unit on hand, but the kit needs 2 of it per kit ordered.
        JsonNode po = receiveStock(Map.of(variantA, new BigDecimal("1")));
        String componentSku = null;
        for (JsonNode line : po.get("lines")) {
            if (line.get("variantId").asText().equals(variantA)) {
                componentSku = line.get("sku").asText();
            }
        }
        assertThat(componentSku).isNotNull();

        JsonNode kit = createKit(categoryId, newProfession(), "For a working Spa Esthetician",
                List.of(variantA), List.of("2"), "800");
        String kitVariantId = kit.get("variantId").asText();
        String kitName = kit.get("name").asText();

        String sessionId = "kit-starve-session-" + UUID.randomUUID();
        // Adding to cart is unaffected — the courtesy check is bypassed entirely for kits.
        cartOk(post("/api/v1/cart/items"), "{\"variantId\":\"" + kitVariantId + "\",\"qty\":1}", sessionId);

        MvcResult checkoutResult = cartRaw(post("/api/v1/checkout"), checkoutRequestBody("kit-starve@example.com"), sessionId);
        assertThat(checkoutResult.getResponse().getStatus()).isEqualTo(409);
        String responseBody = checkoutResult.getResponse().getContentAsString();
        assertThat(responseBody).contains(kitName);
        assertThat(responseBody).doesNotContain(componentSku);
    }

    @Test
    void cancellingAKitOrderReleasesEveryComponentReservation() throws Exception {
        String categoryId = newCategory();
        String variantA = newComponentVariant(new BigDecimal("300"));
        String variantB = newComponentVariant(new BigDecimal("400"));
        receiveStock(Map.of(variantA, new BigDecimal("10"), variantB, new BigDecimal("10")));

        JsonNode kit = createKit(categoryId, newProfession(), "For a Salon Owner kitting out a new station",
                List.of(variantA, variantB), List.of("1", "1"), "600");
        String kitVariantId = kit.get("variantId").asText();

        String sessionId = "kit-cancel-session-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), "{\"variantId\":\"" + kitVariantId + "\",\"qty\":1}", sessionId);
        JsonNode checkoutResult = cartOk(post("/api/v1/checkout"), checkoutRequestBody("kit-cancel@example.com"), sessionId);
        String orderId = checkoutResult.get("order").get("id").asText();

        ok(post("/api/v1/orders/" + orderId + "/cancel"), null);

        var order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        List<StockReservation> reservations = reservationRepository.findByReferenceTypeAndReferenceId(
                "CUSTOMER_ORDER", UUID.fromString(orderId));
        assertThat(reservations).hasSize(2);
        assertThat(reservations).allMatch(r -> r.getStatus() == StockReservation.ReservationStatus.RELEASED);
    }

    @Test
    void aProfessionCanHaveSeveralKitsEachWithItsOwnDescription() throws Exception {
        // Regression test for the old design: Kit used to own a UNIQUE profession_slug, capping the
        // catalogue at one kit per profession. Profession is now its own entity kits merely
        // reference, so a beginner and a pro tier can share one profession.
        String categoryId = newCategory();
        String professionId = newProfession();

        String beginnerVariant = newComponentVariant(new BigDecimal("400"));
        receiveStock(Map.of(beginnerVariant, new BigDecimal("5")));
        JsonNode beginnerKit = createKit(categoryId, professionId, "Everything a first-timer needs",
                List.of(beginnerVariant), List.of("1"), "350");

        String proVariant = newComponentVariant(new BigDecimal("1200"));
        receiveStock(Map.of(proVariant, new BigDecimal("5")));
        JsonNode proKit = createKit(categoryId, professionId, "The full professional set",
                List.of(proVariant), List.of("1"), "1000");

        assertThat(beginnerKit.get("professionId").asText()).isEqualTo(professionId);
        assertThat(proKit.get("professionId").asText()).isEqualTo(professionId);

        JsonNode kitsBody = json.readTree(mvc.perform(get("/api/v1/catalog/kits"))
                .andReturn().getResponse().getContentAsString());
        String beginnerVariantId = beginnerKit.get("variantId").asText();
        String proVariantId = proKit.get("variantId").asText();
        boolean foundBeginner = false;
        boolean foundPro = false;
        for (JsonNode k : kitsBody) {
            if (k.get("variantId").asText().equals(beginnerVariantId)) {
                assertThat(k.get("professionId").asText()).isEqualTo(professionId);
                assertThat(k.get("description").asText()).isEqualTo("Everything a first-timer needs");
                foundBeginner = true;
            }
            if (k.get("variantId").asText().equals(proVariantId)) {
                assertThat(k.get("professionId").asText()).isEqualTo(professionId);
                assertThat(k.get("description").asText()).isEqualTo("The full professional set");
                foundPro = true;
            }
        }
        assertThat(foundBeginner).as("beginner kit should be in the public kits list").isTrue();
        assertThat(foundPro).as("pro kit should be in the public kits list").isTrue();
    }
}
