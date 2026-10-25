package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.repository.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Orders that staff enter for a customer, and the pick-and-pack list on every order (owner, 2026-10-19). A staff order is
 * built from the shop's own basket under the staff member's session and placed through the same checkout, so it is priced,
 * package-picked, stock-held and paid like a shopper's; what differs is who placed it. The pick list is the order's lines in
 * the order they were entered, a package's products following it, each with a tick that is remembered. Same Spring context as
 * {@link com.nbh.backend.packages.PackageIntegrationTest} (identical annotations), so no extra connection pool.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffOrderIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private StockReservationRepository reservationRepository;
    @Autowired private JdbcTemplate jdbc;

    private StaffUser admin;
    private String token;

    @BeforeEach
    void setUp() {
        admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    // ---- helpers (the same ones the package tests have, kept private there) ----

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body, String bearer) throws Exception {
        request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = call(request, body, token);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

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

    private String newCategory() {
        Category c = new Category();
        c.setSlug("staff-order-category-" + UUID.randomUUID().toString().substring(0, 8));
        c.setName(unique("Staff Order Category"));
        return categoryRepository.save(c).getId().toString();
    }

    private String newProfession() throws Exception {
        return ok(post("/api/v1/admin/professions"),
                "{\"label\":\"" + unique("Lash Tech") + "\",\"slug\":\"lash-tech-" + UUID.randomUUID().toString().substring(0, 8) + "\"}")
                .get("id").asText();
    }

    /** A published product with these variants (name, price, colour); returns variant ids by name. */
    private Map<String, String> newProduct(String name, String[]... variants) throws Exception {
        StringBuilder list = new StringBuilder();
        for (String[] v : variants) {
            if (!list.isEmpty()) list.append(",");
            list.append("{\"variantName\":\"").append(v[0]).append("\",\"sellingPrice\":\"").append(v[1])
                    .append("\",\"isActive\":true,\"options\":{\"color\":\"").append(v[0])
                    .append("\",\"size\":null,\"swatchHex\":null,\"colorFamily\":null}}");
        }
        JsonNode product = ok(post("/api/v1/products/draft"),
                "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,\"variants\":[" + list + "]}");
        ok(post("/api/v1/products/" + product.get("id").asText() + "/publish"), null);
        Map<String, String> ids = new HashMap<>();
        for (JsonNode variant : product.get("variants")) {
            ids.put(variant.get("variantName").asText(), variant.get("id").asText());
        }
        return ids;
    }

    private void receiveStock(Map<String, BigDecimal> qtyByVariantId) throws Exception {
        StringBuilder lines = new StringBuilder();
        for (var e : qtyByVariantId.entrySet()) {
            if (!lines.isEmpty()) lines.append(",");
            lines.append("{\"variantId\":\"").append(e.getKey()).append("\",\"qty\":\"").append(e.getValue())
                    .append("\",\"unitCost\":\"100\"}");
        }
        String supplier = ok(post("/api/v1/admin/suppliers"),
                "{\"name\":\"" + unique("Staff Order Supplier") + "\",\"phone\":\"0700000000\"}").get("id").asText();
        JsonNode po = ok(post("/api/v1/admin/purchase-orders"), "{\"supplierId\":\"" + supplier + "\",\"lines\":[" + lines + "]}");
        String poId = po.get("id").asText();
        ok(post("/api/v1/admin/purchase-orders/" + poId + "/send"), null);
        StringBuilder receiveLines = new StringBuilder();
        for (JsonNode line : po.get("lines")) {
            if (!receiveLines.isEmpty()) receiveLines.append(",");
            receiveLines.append("{\"poLineId\":\"").append(line.get("id").asText()).append("\",\"qty\":\"")
                    .append(qtyByVariantId.get(line.get("variantId").asText())).append("\"}");
        }
        ok(post("/api/v1/admin/purchase-orders/" + poId + "/receive"), "{\"lines\":[" + receiveLines + "]}");
    }

    private String addBody(String variantId, int qty, Map<String, String> picks) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variantId", variantId);
        body.put("qty", qty);
        if (picks != null) body.put("packageChoices", picks);
        return json.writeValueAsString(body);
    }

    /** Lashes in two colours (2 in the package), a cleanser (1 in the package) and a plain oil; the package costs 1000 against 1300 apart. */
    private record Fixture(String packageVariantId, String redId, String pinkId, String cleanserId, String oilId) {}

    private Fixture fixture() throws Exception {
        Map<String, String> lashes = newProduct(unique("Staff Lashes"), new String[]{"Ruby Red", "500"}, new String[]{"Rose Pink", "650"});
        Map<String, String> cleanser = newProduct(unique("Staff Cleanser"), new String[]{"Default", "300"});
        Map<String, String> oil = newProduct(unique("Staff Oil"), new String[]{"Default", "450"});
        String red = lashes.get("Ruby Red"), pink = lashes.get("Rose Pink");
        String cleanserId = cleanser.get("Default"), oilId = oil.get("Default");
        receiveStock(Map.of(red, new BigDecimal("10"), pink, new BigDecimal("10"), cleanserId, new BigDecimal("10"),
                oilId, new BigDecimal("20")));
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("name", unique("Staff Package"));
        pkg.put("professionId", newProfession());
        pkg.put("categoryId", newCategory());
        pkg.put("description", "For a technician");
        pkg.put("finalPrice", "1000");
        pkg.put("components", List.of(Map.of("variantId", red, "qty", "2", "customerCanChange", true), Map.of("variantId", cleanserId, "qty", "1")));
        JsonNode created = ok(post("/api/v1/admin/packages"), json.writeValueAsString(pkg));
        return new Fixture(created.get("variantId").asText(), red, pink, cleanserId, oilId);
    }

    /** A basket under a session of its own, the way the staff screen builds one. */
    private String basket(String... lines) throws Exception {
        String session = "staff-" + UUID.randomUUID();
        for (int i = 0; i < lines.length; i++) {
            cartOk(post("/api/v1/cart/items"), lines[i], session);
        }
        return session;
    }

    private static String phone() {
        return "07" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    private Map<String, Object> orderBody(String session, String subtotal, String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cartSession", session);
        body.put("customerName", "Wanjiku Mwangi");
        body.put("phone", phone);
        body.put("email", "staff-order-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        body.put("fulfilmentType", "PICKUP");
        body.put("deliveryAddress", "Simara Mall — Shop B33");
        body.put("expectedSubtotal", subtotal);
        return body;
    }

    private MvcResult place(Map<String, Object> body, String bearer) throws Exception {
        return call(post("/api/v1/admin/orders"), json.writeValueAsString(body), bearer);
    }

    private JsonNode placeOk(Map<String, Object> body) throws Exception {
        MvcResult result = place(body, token);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** A member of staff with one of the shop's default roles, who can sign in (so can be named as the person who agreed a price). */
    private StaffUser newStaff(String roleName) {
        StaffUser user = new StaffUser();
        user.setEmail(roleName.toLowerCase().replace('_', '-') + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode("irrelevant-password"));
        user.setFirstName(roleName.charAt(0) + roleName.substring(1).toLowerCase().replace('_', ' '));
        user.setLastName("Tester " + UUID.randomUUID().toString().substring(0, 4));
        user.setStaffRole(staffRoles.findByName(roleName).orElseThrow());
        return staffUsers.save(user);
    }

    private String tokenOf(StaffUser user) {
        return jwtService.generateAccessToken(user);
    }

    private String viewerToken() {
        return tokenOf(newStaff("VIEWER"));
    }

    private JsonNode placeOk(Map<String, Object> body, String bearer) throws Exception {
        MvcResult result = place(body, bearer);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode okAs(MockHttpServletRequestBuilder request, String body, String bearer) throws Exception {
        MvcResult result = call(request, body, bearer);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    /** One price change as the staff screen sends it. */
    private static Map<String, Object> change(String cartItemId, String newPrice, String reason, StaffUser confirmer, String said) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("cartItemId", cartItemId);
        change.put("newUnitPrice", newPrice);
        change.put("reason", reason);
        if (confirmer != null) change.put("confirmerId", confirmer.getId().toString());
        if (said != null) change.put("whatWasSaid", said);
        return change;
    }

    /** The id of the basket's n-th line, which is what a price change names. */
    private String lineId(String session, int index) throws Exception {
        return cartOk(get("/api/v1/cart"), null, session).get("items").get(index).get("id").asText();
    }

    private long ordersFor(String phone) {
        return jdbc.queryForObject("SELECT count(*) FROM customer_order WHERE guest_phone = ?", Long.class,
                "254" + phone.substring(1));
    }

    // ---- entering an order ----

    @Test
    void aStaffOrderIsPricedAndHeldLikeAShoppersAndRemembersWhoEnteredIt() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.packageVariantId(), 1, Map.of(f.redId(), f.pinkId())), addBody(f.oilId(), 2, null));

        JsonNode placed = placeOk(orderBody(session, "2131", phone()));
        JsonNode order = placed.get("order");

        assertThat(order.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("subtotal").decimalValue()).as("1000 + 2 x (650 - 500) x 1000/1300 for the package, and 2 x 450").isEqualByComparingTo("2131");
        assertThat(order.get("placedBy").asText()).isEqualTo(admin.getFullName());
        assertThat(placed.get("payRef").isNull()).as("the customer can pay later with the link").isFalse();
        assertThat(placed.get("paymentRecordId").asText()).isNotBlank();

        // the lines in the order they were entered: the package, then the oil; the package's own products in the package's order
        JsonNode lines = order.get("items");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("1231");
        assertThat(lines.get(1).get("unitPrice").decimalValue()).isEqualByComparingTo("450");
        JsonNode inside = lines.get(0).get("components");
        assertThat(inside).hasSize(2);
        assertThat(inside.get(0).get("variantId").asText()).as("the colour that was picked, first in the package").isEqualTo(f.pinkId());
        assertThat(inside.get(0).get("qty").decimalValue()).isEqualByComparingTo("2");
        assertThat(inside.get(1).get("variantId").asText()).isEqualTo(f.cleanserId());
        assertThat(jdbc.queryForList("SELECT line_no FROM customer_order_item WHERE order_id = ?::uuid ORDER BY line_no",
                Integer.class, order.get("id").asText())).containsExactly(1, 2, 3, 4);

        // stock is held, as for a shopper
        List<StockReservation> held = reservationRepository.findByReferenceTypeAndReferenceId("CUSTOMER_ORDER",
                UUID.fromString(order.get("id").asText()));
        assertThat(held).hasSize(3);
        assertThat(held).allMatch(r -> r.getStatus() == StockReservation.ReservationStatus.ACTIVE);
    }

    @Test
    void theOrderDoesNotRingTheNewOrderAlertForThePersonWhoEnteredIt() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.oilId(), 1, null));
        String id = placeOk(orderBody(session, "450", phone())).get("order").get("id").asText();

        assertThat(jdbc.queryForObject("SELECT alert_acknowledged_at IS NOT NULL FROM customer_order WHERE id = ?::uuid",
                Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT alert_acknowledged_by FROM customer_order WHERE id = ?::uuid",
                String.class, id)).isEqualTo(admin.getEmail());
    }

    @Test
    void aCustomerWithNoEmailIsSavedAndStillFollowsTheOrderByItsNumber() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.oilId(), 1, null));
        Map<String, Object> body = orderBody(session, "450", phone());
        body.put("email", "");

        JsonNode order = placeOk(body).get("order");

        assertThat(order.get("guestEmail").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT guest_email IS NULL FROM customer_order WHERE id = ?::uuid", Boolean.class,
                order.get("id").asText())).isTrue();
        MvcResult tracked = mvc.perform(get("/api/v1/orders/track/" + order.get("orderNo").asText())).andReturn();
        assertThat(tracked.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anOrderWithNoEmailShowsInTheStaffListsAndSearchesWithoutErrors() throws Exception {
        Fixture f = fixture();
        String customerPhone = phone();
        String session = basket(addBody(f.oilId(), 1, null));
        Map<String, Object> body = orderBody(session, "450", customerPhone);
        body.put("email", null);
        JsonNode order = placeOk(body).get("order");
        String orderNo = order.get("orderNo").asText();

        // the orders list, found by its number and by an email fragment that no order has
        JsonNode byNumber = ok(get("/api/v1/orders?q=" + orderNo), null);
        assertThat(byNumber.get("content")).hasSize(1);
        assertThat(byNumber.get("content").get(0).get("guestEmail").isNull()).isTrue();
        assertThat(ok(get("/api/v1/orders?q=nobody-has-this@example"), null).get("content")).isEmpty();

        // the customers list: one row for the phone, with no email
        JsonNode customers = ok(get("/api/v1/admin/customers?q=" + customerPhone), null);
        assertThat(customers.get("content")).hasSize(1);
        assertThat(customers.get("content").get(0).get("email").isNull()).isTrue();
        assertThat(customers.get("content").get(0).get("name").asText()).isEqualTo("Wanjiku Mwangi");
    }

    @Test
    void staffAreNotHeldToTheShoppersLimitOnOrdersWaitingForPayment() throws Exception {
        Fixture f = fixture();
        String samePhone = phone();
        for (int i = 0; i < 5; i++) {
            String session = basket(addBody(f.oilId(), 1, null));
            placeOk(orderBody(session, "450", samePhone));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order WHERE guest_phone = ?", Integer.class,
                "254" + samePhone.substring(1))).isEqualTo(5);
    }

    @Test
    void staffCanTakeTheMoneyNowAndTheOrderIsPaidTheMomentItExists() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.packageVariantId(), 1, null));
        Map<String, Object> body = orderBody(session, "1000", phone());
        body.put("payNow", Map.of("method", "CASH", "notes", "Paid at the counter"));

        JsonNode placed = placeOk(body);
        JsonNode order = placed.get("order");

        assertThat(order.get("status").asText()).isEqualTo("PAID");
        assertThat(placed.get("payRef").isNull()).as("nothing left to pay").isTrue();
        JsonNode payment = order.get("payments").get(0);
        assertThat(payment.get("method").asText()).isEqualTo("CASH");
        assertThat(payment.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(payment.get("amount").decimalValue()).isEqualByComparingTo("1000");
        List<StockReservation> held = reservationRepository.findByReferenceTypeAndReferenceId("CUSTOMER_ORDER",
                UUID.fromString(order.get("id").asText()));
        assertThat(held).hasSize(2);
        assertThat(held).as("the hold is now the permanent one of a paid order")
                .allMatch(r -> r.getSource() == StockReservation.ReservationSource.ONLINE_ORDER);
    }

    @Test
    void aMpesaOrBankPaymentNeedsItsCodeAndACodeCanPayForOneOrderOnly() throws Exception {
        Fixture f = fixture();

        String noCode = basket(addBody(f.oilId(), 1, null));
        Map<String, Object> missing = orderBody(noCode, "450", phone());
        missing.put("payNow", Map.of("method", "MPESA"));
        MvcResult refused = place(missing, token);
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("M-PESA code");

        String code = "QW" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String first = basket(addBody(f.oilId(), 1, null));
        Map<String, Object> paid = orderBody(first, "450", phone());
        paid.put("payNow", Map.of("method", "MPESA", "reference", code));
        assertThat(placeOk(paid).get("order").get("status").asText()).isEqualTo("PAID");

        String second = basket(addBody(f.oilId(), 1, null));
        String secondPhone = phone();
        Map<String, Object> reused = orderBody(second, "450", secondPhone);
        reused.put("payNow", Map.of("method", "MPESA", "reference", code));
        MvcResult conflict = place(reused, token);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order WHERE guest_phone = ?", Integer.class,
                "254" + secondPhone.substring(1))).as("the refused order does not exist").isZero();
        JsonNode stillThere = cartOk(get("/api/v1/cart"), null, second);
        assertThat(stillThere.get("items")).as("and its basket is still open for another try").hasSize(1);
    }

    @Test
    void aTotalThatMovedSinceTheScreenShowedItPlacesNothing() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.oilId(), 1, null));
        String body = json.writeValueAsString(orderBody(session, "400", phone()));

        MvcResult refused = call(post("/api/v1/admin/orders"), body, token);

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("Some prices changed");
        assertThat(cartOk(get("/api/v1/cart"), null, session).get("items")).hasSize(1);
    }

    @Test
    void aMissingBasketOrMissingPlaceToGoIsRefusedWithAReasonStaffCanActOn() throws Exception {
        Fixture f = fixture();

        MvcResult empty = place(orderBody("staff-" + UUID.randomUUID(), "0", phone()), token);
        assertThat(empty.getResponse().getStatus()).isEqualTo(422);
        assertThat(empty.getResponse().getContentAsString()).contains("Add at least one product");

        String session = basket(addBody(f.oilId(), 1, null));
        Map<String, Object> noShop = orderBody(session, "450", phone());
        noShop.put("deliveryAddress", "");
        MvcResult noShopResult = place(noShop, token);
        assertThat(noShopResult.getResponse().getStatus()).isEqualTo(422);
        assertThat(noShopResult.getResponse().getContentAsString()).contains("shop");

        Map<String, Object> noMethod = orderBody(session, "450", phone());
        noMethod.put("fulfilmentType", "DELIVERY_COURIER");
        noMethod.put("deliveryAddress", "Kilimani, Argwings Kodhek Rd");
        MvcResult noMethodResult = place(noMethod, token);
        assertThat(noMethodResult.getResponse().getStatus()).isEqualTo(422);
        assertThat(noMethodResult.getResponse().getContentAsString()).contains("how the delivery travels");

        Map<String, Object> delivery = orderBody(session, "450", phone());
        delivery.put("fulfilmentType", "DELIVERY_COURIER");
        delivery.put("deliveryMethod", "MATATU");
        delivery.put("deliveryAddress", "Kilimani, Argwings Kodhek Rd");
        JsonNode placed = placeOk(delivery);
        assertThat(placed.get("order").get("fulfilmentType").asText()).isEqualTo("DELIVERY_COURIER");
        assertThat(placed.get("order").get("deliveryMethod").asText()).isEqualTo("MATATU");
    }

    @Test
    void anItemThatIsOutOfStockIsRefusedInWordsForStaffAndNothingIsPlaced() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.oilId(), 2, null));
        // someone else took the shelf after the basket was built: an order for 19 of the 20 holds them (through the
        // ordinary order, never by writing to the stock tables, which the whole-ledger checks of other tests read)
        String other = basket(addBody(f.oilId(), 19, null));
        placeOk(orderBody(other, "8550", phone()));

        MvcResult refused = place(orderBody(session, "900", phone()), token);

        assertThat(refused.getResponse().getStatus()).isGreaterThanOrEqualTo(400).isLessThan(500);
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("update your cart");
        assertThat(cartOk(get("/api/v1/cart"), null, session).get("items")).as("the basket is still open").hasSize(1);
    }

    // ---- who may enter an order ----

    @Test
    void everyRoleButTheReadOnlyOneCanEnterAnOrder() throws Exception {
        Fixture f = fixture();
        for (String role : new String[]{"MANAGER", "CASHIER", "STOCK_CONTROLLER"}) {
            StaffUser who = newStaff(role);
            String session = basket(addBody(f.oilId(), 1, null));
            JsonNode placed = placeOk(orderBody(session, "450", phone()), tokenOf(who));
            assertThat(placed.get("order").get("placedBy").asText()).as(role).isEqualTo(who.getFullName());
            assertThat(placed.get("order").get("status").asText()).as(role).isEqualTo("PENDING_PAYMENT");
        }

        String session = basket(addBody(f.oilId(), 1, null));
        String customerPhone = phone();
        MvcResult viewer = place(orderBody(session, "450", customerPhone), viewerToken());
        assertThat(viewer.getResponse().getStatus()).isEqualTo(403);
        assertThat(ordersFor(customerPhone)).isZero();
        assertThat(cartOk(get("/api/v1/cart"), null, session).get("items")).as("nothing was placed").hasSize(1);

        MvcResult anonymous = mvc.perform(post("/api/v1/admin/orders").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(orderBody(session, "450", phone())))).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isIn(401, 403);
    }

    @Test
    void onlyPeopleWhoTakePaymentsCanRecordOneWhileEnteringAnOrder() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.oilId(), 1, null));
        String customerPhone = phone();
        Map<String, Object> body = orderBody(session, "450", customerPhone);
        body.put("payNow", Map.of("method", "CASH"));

        MvcResult stockController = place(body, tokenOf(newStaff("STOCK_CONTROLLER")));
        assertThat(stockController.getResponse().getStatus()).isEqualTo(403);
        assertThat(stockController.getResponse().getContentAsString()).contains("cannot record a payment");
        assertThat(ordersFor(customerPhone)).as("nothing was placed").isZero();

        JsonNode cashier = placeOk(body, tokenOf(newStaff("CASHIER")));
        assertThat(cashier.get("order").get("status").asText()).as("a cashier takes the money at the counter").isEqualTo("PAID");
    }

    // ---- the same basket submitted twice ----

    @Test
    void submittingTheSameBasketAgainTellsStaffTheOrderWasAlreadyPlaced() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.oilId(), 1, null));
        String customerPhone = phone();
        Map<String, Object> body = orderBody(session, "450", customerPhone);
        placeOk(body);

        MvcResult again = place(body, token);

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(again.getResponse().getContentAsString()).contains("already placed");
        assertThat(ordersFor(customerPhone)).isEqualTo(1);
    }

    /**
     * The staff version of the double-click bug found in the 2026-10-06 live QA (see {@link ConcurrentCheckoutRaceTest}),
     * and found again in the packages audit: the order screen read the basket before the checkout lock was taken, so a second
     * submit that waited for the lock carried on with a copy that still said "open". A cash sale with no receipt number
     * is the worst case, because nothing else (a reused M-PESA code) would have stopped the copy being paid as well.
     */
    @Test
    void aStaffOrderSubmittedSeveralTimesAtTheSameMomentMakesExactlyOneOrder() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.oilId(), 2, null));
        String customerPhone = phone();
        Map<String, Object> order = orderBody(session, "900", customerPhone);
        order.put("payNow", Map.of("method", "CASH"));
        String body = json.writeValueAsString(order);

        int submits = 6;
        ExecutorService pool = Executors.newFixedThreadPool(submits);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < submits; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return call(post("/api/v1/admin/orders"), body, token).getResponse().getStatus();
            }));
        }
        go.countDown();
        int placed = 0;
        int refused = 0;
        for (Future<Integer> result : results) {
            int status = result.get();
            if (status == 200) placed++;
            else if (status == 409) refused++;
        }
        pool.shutdown();

        assertThat(placed).as("submits that made an order").isEqualTo(1);
        assertThat(refused).as("submits told it was already placed").isEqualTo(submits - 1);
        assertThat(ordersFor(customerPhone)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_reservation WHERE variant_id = ?::uuid", Long.class,
                f.oilId())).as("stock holds on the product").isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payment_record p JOIN customer_order o ON o.id = p.order_id
                WHERE o.guest_phone = ? AND p.status = 'SUCCESS'""", Long.class, "254" + customerPhone.substring(1)))
                .as("payments recorded").isEqualTo(1L);
    }

    // ---- the customer on the phone ----

    private JsonNode lookup(String number, String bearer) throws Exception {
        return okAs(get("/api/v1/admin/orders/customer-lookup").param("phone", number), null, bearer);
    }

    @Test
    void aReturningCustomerIsFoundByPhoneInAnySpellingAndTheNewestOrderFillsTheForm() throws Exception {
        Fixture f = fixture();
        String customerPhone = phone();
        Map<String, Object> first = orderBody(basket(addBody(f.oilId(), 1, null)), "450", customerPhone);
        first.put("customerName", "Wanjiku Mwangi");
        String firstEmail = "wanjiku-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        first.put("email", firstEmail);
        placeOk(first);

        JsonNode found = lookup("+254" + customerPhone.substring(1), token);
        assertThat(found.get("found").asBoolean()).isTrue();
        assertThat(found.get("phone").asText()).as("in the form the shop stores it").isEqualTo("254" + customerPhone.substring(1));
        assertThat(found.get("name").asText()).isEqualTo("Wanjiku Mwangi");
        assertThat(found.get("email").asText()).isEqualTo(firstEmail);
        assertThat(found.get("orders").asLong()).isEqualTo(1);
        assertThat(found.get("fulfilmentType").asText()).isEqualTo("PICKUP");
        assertThat(found.get("deliveryAddress").asText()).isEqualTo("Simara Mall — Shop B33");

        // a later order of theirs is what the form starts from, however the number is written
        Map<String, Object> second = orderBody(basket(addBody(f.oilId(), 1, null)), "450", customerPhone);
        second.put("customerName", "W. Mwangi");
        second.put("email", "");
        second.put("fulfilmentType", "DELIVERY_COURIER");
        second.put("deliveryMethod", "MATATU");
        second.put("deliveryAddress", "Kilimani, Argwings Kodhek Rd");
        placeOk(second);

        String spaced = customerPhone.substring(0, 4) + " " + customerPhone.substring(4, 7) + " " + customerPhone.substring(7);
        JsonNode again = lookup(spaced, token);
        assertThat(again.get("orders").asLong()).isEqualTo(2);
        assertThat(again.get("name").asText()).isEqualTo("W. Mwangi");
        assertThat(again.get("email").isNull()).isTrue();
        assertThat(again.get("fulfilmentType").asText()).isEqualTo("DELIVERY_COURIER");
        assertThat(again.get("deliveryMethod").asText()).isEqualTo("MATATU");
        assertThat(again.get("deliveryAddress").asText()).isEqualTo("Kilimani, Argwings Kodhek Rd");
    }

    @Test
    void aNumberThatNeverOrderedIsNotFoundAndAHalfNumberIsRefused() throws Exception {
        JsonNode none = lookup(phone(), token);
        assertThat(none.get("found").asBoolean()).isFalse();
        assertThat(none.get("name").isNull()).isTrue();
        assertThat(none.get("orders").asLong()).isZero();

        MvcResult half = call(get("/api/v1/admin/orders/customer-lookup").param("phone", "0720"), null, token);
        assertThat(half.getResponse().getStatus()).as("only a whole number can be looked up, so customers cannot be browsed")
                .isEqualTo(422);
    }

    @Test
    void theCustomerLookupIsForPeopleWhoCanEnterOrders() throws Exception {
        MvcResult viewer = call(get("/api/v1/admin/orders/customer-lookup").param("phone", phone()), null, viewerToken());
        assertThat(viewer.getResponse().getStatus()).isEqualTo(403);
        MvcResult cashier = call(get("/api/v1/admin/orders/customer-lookup").param("phone", phone()), null,
                tokenOf(newStaff("CASHIER")));
        assertThat(cashier.getResponse().getStatus()).isEqualTo(200);
        MvcResult anonymous = mvc.perform(get("/api/v1/admin/orders/customer-lookup").param("phone", phone())).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isIn(401, 403);
    }

    // ---- changing a price while entering an order ----

    /** An order for one oil whose price a cashier changed, with the manager named as the person who agreed. */
    private JsonNode enterWithAgreedPrice(Fixture f, StaffUser enteredBy, StaffUser agreedWith, String price) throws Exception {
        String session = basket(addBody(f.oilId(), 1, null));
        Map<String, Object> body = orderBody(session, price, phone());
        body.put("priceChanges", List.of(change(lineId(session, 0), price, "Regular customer", agreedWith,
                "Said " + price + " is fine")));
        return placeOk(body, tokenOf(enteredBy)).get("order");
    }

    @Test
    void aManagerChangesAPriceWithAReasonAndTheCustomerIsChargedThatPrice() throws Exception {
        Fixture f = fixture();
        StaffUser manager = newStaff("MANAGER");
        String session = basket(addBody(f.oilId(), 2, null));
        Map<String, Object> body = orderBody(session, "800", phone());
        body.put("priceChanges", List.of(change(lineId(session, 0), "400", "Regular customer, buys every week", null, null)));

        JsonNode order = placeOk(body, tokenOf(manager)).get("order");

        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("800");
        assertThat(order.get("grandTotal").decimalValue()).isEqualByComparingTo("800");
        assertThat(order.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("400");
        assertThat(order.get("items").get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("800");
        assertThat(order.get("payments").get(0).get("amount").decimalValue()).as("the customer is asked for the new price")
                .isEqualByComparingTo("800");

        JsonNode recorded = order.get("priceChanges");
        assertThat(recorded).hasSize(1);
        JsonNode record = recorded.get(0);
        assertThat(record.get("listUnitPrice").decimalValue()).as("the shop's own price is kept").isEqualByComparingTo("450");
        assertThat(record.get("newUnitPrice").decimalValue()).isEqualByComparingTo("400");
        assertThat(record.get("qty").decimalValue()).isEqualByComparingTo("2");
        assertThat(record.get("amountOff").decimalValue()).as("(450 - 400) x 2").isEqualByComparingTo("100");
        assertThat(record.get("reason").asText()).isEqualTo("Regular customer, buys every week");
        assertThat(record.get("enteredBy").asText()).isEqualTo(manager.getFullName());
        assertThat(record.get("status").asText()).as("a manager needs nobody's OK").isEqualTo("NOT_NEEDED");
        assertThat(record.get("confirmer").isNull()).isTrue();
        assertThat(record.get("orderNo").asText()).isEqualTo(order.get("orderNo").asText());
        assertThat(record.get("itemId").asText()).isEqualTo(order.get("items").get(0).get("itemId").asText());

        // still there when staff read the order afresh
        JsonNode reread = ok(get("/api/v1/orders/" + order.get("id").asText()), null);
        assertThat(reread.get("priceChanges")).hasSize(1);
        assertThat(reread.get("priceChanges").get(0).get("id").asText()).isEqualTo(record.get("id").asText());

        // an order with no price change shows none
        JsonNode plain = placeOk(orderBody(basket(addBody(f.oilId(), 1, null)), "450", phone()), tokenOf(manager)).get("order");
        assertThat(plain.get("priceChanges")).isEmpty();
    }

    @Test
    void aCashierMustNameWhoAgreedTheNewPriceAndWriteDownWhatWasSaid() throws Exception {
        Fixture f = fixture();
        StaffUser cashier = newStaff("CASHIER");
        StaffUser manager = newStaff("MANAGER");
        String session = basket(addBody(f.oilId(), 1, null));
        String line = lineId(session, 0);
        String customerPhone = phone();

        Map<String, Object> nobody = orderBody(session, "400", customerPhone);
        nobody.put("priceChanges", List.of(change(line, "400", "Loyal customer", null, null)));
        MvcResult noOne = place(nobody, tokenOf(cashier));
        assertThat(noOne.getResponse().getStatus()).isEqualTo(422);
        assertThat(noOne.getResponse().getContentAsString()).contains("who agreed");

        Map<String, Object> silent = orderBody(session, "400", customerPhone);
        silent.put("priceChanges", List.of(change(line, "400", "Loyal customer", manager, null)));
        MvcResult nothingSaid = place(silent, tokenOf(cashier));
        assertThat(nothingSaid.getResponse().getStatus()).isEqualTo(422);
        assertThat(nothingSaid.getResponse().getContentAsString()).contains("Write down what");
        assertThat(ordersFor(customerPhone)).as("nothing was placed").isZero();

        Map<String, Object> agreed = orderBody(session, "400", customerPhone);
        agreed.put("priceChanges", List.of(change(line, "400", "Loyal customer", manager,
                "Said KES 400 is fine for her, she buys every week")));
        JsonNode order = placeOk(agreed, tokenOf(cashier)).get("order");

        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("400");
        JsonNode record = order.get("priceChanges").get(0);
        assertThat(record.get("status").asText()).isEqualTo("PENDING");
        assertThat(record.get("confirmer").asText()).isEqualTo(manager.getFullName());
        assertThat(record.get("whatWasSaid").asText()).isEqualTo("Said KES 400 is fine for her, she buys every week");
        assertThat(record.get("enteredBy").asText()).isEqualTo(cashier.getFullName());
        assertThat(record.get("answeredAt").isNull()).isTrue();
    }

    @Test
    void theNamedConfirmerSeesTheChangeWaitingAndConfirmsOrDisputesIt() throws Exception {
        Fixture f = fixture();
        StaffUser cashier = newStaff("CASHIER");
        StaffUser manager = newStaff("MANAGER");
        String managerToken = tokenOf(manager);
        JsonNode first = enterWithAgreedPrice(f, cashier, manager, "400");
        JsonNode second = enterWithAgreedPrice(f, cashier, manager, "420");

        JsonNode waiting = okAs(get("/api/v1/admin/price-changes/waiting"), null, managerToken);
        assertThat(waiting).hasSize(2);
        assertThat(waiting.get(0).get("orderNo").asText()).as("the longest waiting first").isEqualTo(first.get("orderNo").asText());
        assertThat(okAs(get("/api/v1/admin/price-changes/waiting/count"), null, managerToken).get("count").asInt()).isEqualTo(2);
        // on nobody else's dashboard, and not for a role that cannot approve
        assertThat(okAs(get("/api/v1/admin/price-changes/waiting"), null, tokenOf(newStaff("MANAGER")))).isEmpty();
        assertThat(call(get("/api/v1/admin/price-changes/waiting"), null, tokenOf(cashier)).getResponse().getStatus())
                .isEqualTo(403);

        String firstId = waiting.get(0).get("id").asText();
        String secondId = waiting.get(1).get("id").asText();
        JsonNode confirmed = okAs(post("/api/v1/admin/price-changes/" + firstId + "/confirm"),
                "{\"note\":\"Yes, I told her 400\"}", managerToken);
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("answeredBy").asText()).isEqualTo(manager.getFullName());
        assertThat(confirmed.get("answerNote").asText()).isEqualTo("Yes, I told her 400");
        assertThat(confirmed.get("answeredAt").isNull()).isFalse();

        MvcResult twice = call(post("/api/v1/admin/price-changes/" + firstId + "/dispute"), "{\"note\":\"changed my mind\"}",
                managerToken);
        assertThat(twice.getResponse().getStatus()).as("an answer is final").isEqualTo(409);

        MvcResult noNote = call(post("/api/v1/admin/price-changes/" + secondId + "/dispute"), "{}", managerToken);
        assertThat(noNote.getResponse().getStatus()).isEqualTo(422);
        assertThat(noNote.getResponse().getContentAsString()).contains("what is wrong");
        JsonNode disputed = okAs(post("/api/v1/admin/price-changes/" + secondId + "/dispute"),
                "{\"note\":\"I never agreed to 420\"}", managerToken);
        assertThat(disputed.get("status").asText()).isEqualTo("DISPUTED");

        assertThat(okAs(get("/api/v1/admin/price-changes/waiting"), null, managerToken)).as("nothing left waiting").isEmpty();
        // a dispute puts the change in front of the owner but changes nothing about the order
        JsonNode after = ok(get("/api/v1/orders/" + second.get("id").asText()), null);
        assertThat(after.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(after.get("grandTotal").decimalValue()).isEqualByComparingTo("420");
        assertThat(after.get("priceChanges").get(0).get("status").asText()).isEqualTo("DISPUTED");
        assertThat(after.get("priceChanges").get(0).get("answerNote").asText()).isEqualTo("I never agreed to 420");
    }

    @Test
    void thePersonWhoEnteredAPriceChangeCannotAnswerItButAnotherApproverCan() throws Exception {
        Fixture f = fixture();
        StaffUser enteredBy = newStaff("MANAGER");
        StaffUser namedConfirmer = newStaff("MANAGER");
        JsonNode order = enterWithAgreedPrice(f, enteredBy, namedConfirmer, "400");
        String id = order.get("priceChanges").get(0).get("id").asText();

        MvcResult own = call(post("/api/v1/admin/price-changes/" + id + "/confirm"), null, tokenOf(enteredBy));
        assertThat(own.getResponse().getStatus()).isEqualTo(403);

        // the owner can answer for a manager who is away, and the record says who did
        StaffUser other = newStaff("MANAGER");
        JsonNode answered = okAs(post("/api/v1/admin/price-changes/" + id + "/confirm"), null, tokenOf(other));
        assertThat(answered.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(answered.get("answeredBy").asText()).isEqualTo(other.getFullName());
        assertThat(answered.get("confirmer").asText()).as("the person it was agreed with is still named").isEqualTo(namedConfirmer.getFullName());
    }

    @Test
    void thePersonAgreedWithMustBeSomeoneWhoMayApproveAndCanSignIn() throws Exception {
        Fixture f = fixture();
        StaffUser cashier = newStaff("CASHIER");
        StaffUser away = newStaff("MANAGER");
        away.setEnabled(false);
        staffUsers.save(away);
        StaffUser notYetSignedIn = newStaff("MANAGER");
        notYetSignedIn.setMustChangePassword(true);
        staffUsers.save(notYetSignedIn);
        String session = basket(addBody(f.oilId(), 1, null));
        String line = lineId(session, 0);
        String customerPhone = phone();

        for (StaffUser named : new StaffUser[]{cashier, away, notYetSignedIn, newStaff("STOCK_CONTROLLER")}) {
            Map<String, Object> body = orderBody(session, "400", customerPhone);
            body.put("priceChanges", List.of(change(line, "400", "Loyal customer", named, "OK")));
            MvcResult refused = place(body, tokenOf(cashier));
            assertThat(refused.getResponse().getStatus()).as(named.getEmail()).isEqualTo(422);
            assertThat(refused.getResponse().getContentAsString()).contains("from the list");
        }
        Map<String, Object> stranger = orderBody(session, "400", customerPhone);
        Map<String, Object> strangerChange = change(line, "400", "Loyal customer", null, "OK");
        strangerChange.put("confirmerId", UUID.randomUUID().toString());
        stranger.put("priceChanges", List.of(strangerChange));
        assertThat(place(stranger, tokenOf(cashier)).getResponse().getStatus()).isEqualTo(422);
        assertThat(ordersFor(customerPhone)).isZero();
    }

    @Test
    void thePriceApproversAreTheOwnerAndManagersWhoCanSignInNeverThePersonAsking() throws Exception {
        StaffUser cashier = newStaff("CASHIER");
        StaffUser manager = newStaff("MANAGER");
        StaffUser away = newStaff("MANAGER");
        away.setEnabled(false);
        staffUsers.save(away);
        StaffUser stockController = newStaff("STOCK_CONTROLLER");

        List<String> forCashier = new ArrayList<>();
        okAs(get("/api/v1/admin/orders/price-approvers"), null, tokenOf(cashier)).forEach(a -> forCashier.add(a.get("id").asText()));
        assertThat(forCashier).contains(manager.getId().toString())
                .doesNotContain(away.getId().toString(), cashier.getId().toString(), stockController.getId().toString());

        StaffUser colleague = newStaff("MANAGER");
        JsonNode forManager = okAs(get("/api/v1/admin/orders/price-approvers"), null, tokenOf(manager));
        List<String> managerSees = new ArrayList<>();
        forManager.forEach(a -> managerSees.add(a.get("id").asText()));
        assertThat(managerSees).as("a colleague, but not themselves").contains(colleague.getId().toString())
                .doesNotContain(manager.getId().toString());
        for (JsonNode approver : forManager) {
            if (colleague.getId().toString().equals(approver.get("id").asText())) {
                assertThat(approver.get("name").asText()).isEqualTo(colleague.getFullName());
                assertThat(approver.get("role").asText()).isEqualTo("Manager");
            }
        }

        assertThat(call(get("/api/v1/admin/orders/price-approvers"), null, viewerToken()).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void theTotalMustIncludeThePriceChangeOrNothingIsPlaced() throws Exception {
        Fixture f = fixture();
        StaffUser manager = newStaff("MANAGER");
        String session = basket(addBody(f.oilId(), 1, null));
        String customerPhone = phone();
        long recordedBefore = jdbc.queryForObject("SELECT count(*) FROM order_price_change", Long.class);
        Map<String, Object> body = orderBody(session, "450", customerPhone); // the total WITHOUT the change
        body.put("priceChanges", List.of(change(lineId(session, 0), "400", "Loyal customer", null, null)));

        MvcResult refused = place(body, tokenOf(manager));

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("Some prices changed");
        assertThat(ordersFor(customerPhone)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_price_change", Long.class)).isEqualTo(recordedBefore);
    }

    @Test
    void aChangeThatChangesNothingNamesNoSuchLineOrRepeatsALineIsRefused() throws Exception {
        Fixture f = fixture();
        String manager = tokenOf(newStaff("MANAGER"));
        String session = basket(addBody(f.oilId(), 1, null), addBody(f.cleanserId(), 1, null));
        String oil = lineId(session, 0);
        String customerPhone = phone();

        Map<String, Object> same = orderBody(session, "750", customerPhone);
        same.put("priceChanges", List.of(change(oil, "450", "No change really", null, null)));
        MvcResult noChange = place(same, manager);
        assertThat(noChange.getResponse().getStatus()).isEqualTo(422);
        assertThat(noChange.getResponse().getContentAsString()).contains("already 450");

        Map<String, Object> stranger = orderBody(session, "750", customerPhone);
        stranger.put("priceChanges", List.of(change(UUID.randomUUID().toString(), "100", "Not in this basket", null, null)));
        MvcResult noSuchLine = place(stranger, manager);
        assertThat(noSuchLine.getResponse().getStatus()).isEqualTo(422);
        assertThat(noSuchLine.getResponse().getContentAsString()).contains("no longer in the basket");

        Map<String, Object> twice = orderBody(session, "750", customerPhone);
        twice.put("priceChanges", List.of(change(oil, "400", "First", null, null), change(oil, "350", "Second", null, null)));
        MvcResult repeated = place(twice, manager);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(422);
        assertThat(repeated.getResponse().getContentAsString()).contains("only have one price change");

        Map<String, Object> negative = orderBody(session, "750", customerPhone);
        negative.put("priceChanges", List.of(change(oil, "-5", "Below nothing", null, null)));
        assertThat(place(negative, manager).getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> noReason = orderBody(session, "750", customerPhone);
        noReason.put("priceChanges", List.of(change(oil, "400", "", null, null)));
        assertThat(place(noReason, manager).getResponse().getStatus()).as("a reason is always needed").isEqualTo(400);

        assertThat(ordersFor(customerPhone)).as("nothing was placed by any of them").isZero();
    }

    @Test
    void theWholePriceOfAPackageLineCanBeChangedAndItsProductsAreLeftAlone() throws Exception {
        Fixture f = fixture();
        StaffUser manager = newStaff("MANAGER");
        String session = basket(addBody(f.packageVariantId(), 1, Map.of(f.redId(), f.pinkId())));
        Map<String, Object> body = orderBody(session, "1200", phone());
        body.put("priceChanges", List.of(change(lineId(session, 0), "1200", "Package for a new salon, 31 off", null, null)));

        JsonNode order = placeOk(body, tokenOf(manager)).get("order");

        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("1200");
        JsonNode packageLine = order.get("items").get(0);
        assertThat(packageLine.get("unitPrice").decimalValue()).isEqualByComparingTo("1200");
        assertThat(packageLine.get("components")).as("the package's products are listed as they were picked").hasSize(2);
        JsonNode record = order.get("priceChanges").get(0);
        assertThat(record.get("listUnitPrice").decimalValue()).as("1000 + 2 x (650 - 500) x 1000/1300").isEqualByComparingTo("1231");
        assertThat(record.get("amountOff").decimalValue()).isEqualByComparingTo("31");
        assertThat(record.get("itemId").asText()).as("on the package's own line").isEqualTo(packageLine.get("itemId").asText());
    }

    @Test
    void aLineCanBeGivenFreeButTheWholeOrderCannotComeToNothing() throws Exception {
        Fixture f = fixture();
        String manager = tokenOf(newStaff("MANAGER"));
        String session = basket(addBody(f.oilId(), 1, null), addBody(f.cleanserId(), 1, null));
        String oil = lineId(session, 0);
        String cleanser = lineId(session, 1);
        String customerPhone = phone();

        Map<String, Object> everything = orderBody(session, "0", customerPhone);
        everything.put("priceChanges", List.of(change(oil, "0", "Gift", null, null), change(cleanser, "0", "Gift", null, null)));
        MvcResult nothing = place(everything, manager);
        assertThat(nothing.getResponse().getStatus()).isEqualTo(422);
        assertThat(nothing.getResponse().getContentAsString()).contains("come to nothing");
        assertThat(ordersFor(customerPhone)).isZero();

        Map<String, Object> oneFree = orderBody(session, "300", phone());
        oneFree.put("priceChanges", List.of(change(oil, "0", "Free sample for a first order", null, null)));
        JsonNode order = placeOk(oneFree, manager).get("order");
        assertThat(order.get("subtotal").decimalValue()).isEqualByComparingTo("300");
        assertThat(order.get("priceChanges").get(0).get("amountOff").decimalValue()).isEqualByComparingTo("450");
    }

    @Test
    void thePriceChangeListIsForPeopleWhoOverseeTheMoney() throws Exception {
        Fixture f = fixture();
        StaffUser cashier = newStaff("CASHIER");
        StaffUser manager = newStaff("MANAGER");
        JsonNode order = enterWithAgreedPrice(f, cashier, manager, "400");
        String orderNo = order.get("orderNo").asText();

        assertThat(namesOrder(okAs(get("/api/v1/admin/price-changes"), null, token), orderNo)).as("the last 30 days").isTrue();
        assertThat(namesOrder(okAs(get("/api/v1/admin/price-changes").param("status", "PENDING"), null, token), orderNo)).isTrue();
        assertThat(namesOrder(okAs(get("/api/v1/admin/price-changes").param("status", "CONFIRMED"), null, token), orderNo))
                .as("not once it is waiting for an answer").isFalse();
        assertThat(namesOrder(okAs(get("/api/v1/admin/price-changes")
                .param("from", "2020-01-01T00:00:00Z").param("to", "2020-01-02T00:00:00Z"), null, token), orderNo))
                .as("another period").isFalse();

        // only those who approve prices or oversee the money may look: not the Viewer, a cashier or a stock controller
        assertThat(call(get("/api/v1/admin/price-changes"), null, viewerToken()).getResponse().getStatus())
                .as("not the read-only Viewer (owner, 2026-10-23)").isEqualTo(403);
        assertThat(call(get("/api/v1/admin/price-changes"), null, tokenOf(cashier)).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get("/api/v1/admin/price-changes"), null, tokenOf(newStaff("STOCK_CONTROLLER"))).getResponse().getStatus())
                .isEqualTo(403);
    }

    private static boolean namesOrder(JsonNode page, String orderNo) {
        for (JsonNode row : page.get("content")) {
            if (orderNo.equals(row.get("orderNo").asText())) return true;
        }
        return false;
    }

    // ---- the pick-and-pack list ----

    private List<String> componentIds(JsonNode order) {
        List<String> ids = new ArrayList<>();
        for (JsonNode line : order.get("items")) {
            if (line.get("components").isEmpty()) {
                ids.add(line.get("itemId").asText());
            } else {
                line.get("components").forEach(c -> ids.add(c.get("itemId").asText()));
            }
        }
        return ids;
    }

    private JsonNode packed(String orderId, List<String> itemIds, boolean packed) throws Exception {
        return ok(post("/api/v1/orders/" + orderId + "/packed"), json.writeValueAsString(
                Map.of("itemIds", itemIds, "packed", packed)));
    }

    private JsonNode paidPackageOrder(Fixture f) throws Exception {
        String session = basket(addBody(f.packageVariantId(), 1, Map.of(f.redId(), f.pinkId())), addBody(f.oilId(), 1, null));
        // 1231 for the package with pink (1000 + 2 x 150 x 1000/1300), 450 for the oil
        Map<String, Object> body = orderBody(session, "1681", phone());
        body.put("payNow", Map.of("method", "CASH"));
        return placeOk(body).get("order");
    }

    @Test
    void staffTickTheProductsOfThePickListAndTheTickIsRemembered() throws Exception {
        Fixture f = fixture();
        JsonNode order = paidPackageOrder(f);
        String id = order.get("id").asText();
        List<String> pickable = componentIds(order);
        assertThat(pickable).as("the package's two products and the oil").hasSize(3);

        JsonNode after = packed(id, List.of(pickable.get(0)), true);
        JsonNode firstTick = after.get("items").get(0).get("components").get(0);
        assertThat(firstTick.get("packedAt").isNull()).isFalse();
        assertThat(firstTick.get("packedBy").asText()).isEqualTo(admin.getFullName());
        assertThat(after.get("items").get(0).get("components").get(1).get("packedAt").isNull()).isTrue();
        String when = firstTick.get("packedAt").asText();

        // ticking it again changes nothing: the first packer and time stay
        JsonNode again = packed(id, List.of(pickable.get(0), pickable.get(1)), true);
        assertThat(again.get("items").get(0).get("components").get(0).get("packedAt").asText()).isEqualTo(when);
        assertThat(again.get("items").get(0).get("components").get(1).get("packedAt").isNull()).isFalse();

        // and it is still there when the order is read afresh
        JsonNode reread = ok(get("/api/v1/orders/" + id), null);
        assertThat(reread.get("items").get(0).get("components").get(0).get("packedAt").asText()).isEqualTo(when);

        // a tick can be taken off
        JsonNode undone = packed(id, List.of(pickable.get(0)), false);
        assertThat(undone.get("items").get(0).get("components").get(0).get("packedAt").isNull()).isTrue();
        assertThat(undone.get("items").get(0).get("components").get(1).get("packedAt").isNull()).isFalse();
    }

    @Test
    void aPackagesOwnLineIsNotPickedSoItCannotBeTicked() throws Exception {
        Fixture f = fixture();
        JsonNode order = paidPackageOrder(f);
        String packageLine = order.get("items").get(0).get("itemId").asText();

        MvcResult refused = call(post("/api/v1/orders/" + order.get("id").asText() + "/packed"),
                json.writeValueAsString(Map.of("itemIds", List.of(packageLine), "packed", true)), token);

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("tick the products inside it");
    }

    @Test
    void aLineOfAnotherOrderOrAnEmptyListIsRefused() throws Exception {
        Fixture f = fixture();
        JsonNode order = paidPackageOrder(f);
        JsonNode other = paidPackageOrder(f);
        String id = order.get("id").asText();

        MvcResult foreign = call(post("/api/v1/orders/" + id + "/packed"),
                json.writeValueAsString(Map.of("itemIds", List.of(componentIds(other).get(0)), "packed", true)), token);
        assertThat(foreign.getResponse().getStatus()).isEqualTo(422);

        MvcResult none = call(post("/api/v1/orders/" + id + "/packed"),
                json.writeValueAsString(Map.of("itemIds", List.of(), "packed", true)), token);
        assertThat(none.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void theListCanOnlyBeTickedFromPaidUntilTheOrderLeavesTheShop() throws Exception {
        Fixture f = fixture();

        // not paid yet
        String session = basket(addBody(f.packageVariantId(), 1, null));
        JsonNode unpaid = placeOk(orderBody(session, "1000", phone())).get("order");
        MvcResult early = call(post("/api/v1/orders/" + unpaid.get("id").asText() + "/packed"),
                json.writeValueAsString(Map.of("itemIds", List.of(componentIds(unpaid).get(0)), "packed", true)), token);
        assertThat(early.getResponse().getStatus()).isEqualTo(422);

        // paid, then picking, then ready: still open
        JsonNode order = paidPackageOrder(f);
        String id = order.get("id").asText();
        ok(post("/api/v1/orders/" + id + "/start-picking"), null);
        packed(id, List.of(componentIds(order).get(0)), true);
        ok(post("/api/v1/orders/" + id + "/mark-ready"), null);
        packed(id, List.of(componentIds(order).get(1)), true);

        // cancelled: closed
        ok(post("/api/v1/orders/" + id + "/cancel"), "{\"reason\":\"test\"}");
        MvcResult late = call(post("/api/v1/orders/" + id + "/packed"),
                json.writeValueAsString(Map.of("itemIds", List.of(componentIds(order).get(2)), "packed", true)), token);
        assertThat(late.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void theOrdersListCountsWhatWasBoughtNotTheProductsInsideAPackage() throws Exception {
        Fixture f = fixture();
        JsonNode order = paidPackageOrder(f);

        JsonNode list = ok(get("/api/v1/orders?q=" + order.get("orderNo").asText()), null);

        assertThat(list.get("content")).hasSize(1);
        assertThat(list.get("content").get(0).get("itemCount").asInt())
                .as("the package and the oil: the package's own two products are not counted again").isEqualTo(2);
    }

    @Test
    void onlyAManagerCanTick() throws Exception {
        Fixture f = fixture();
        JsonNode order = paidPackageOrder(f);

        MvcResult viewer = call(post("/api/v1/orders/" + order.get("id").asText() + "/packed"),
                json.writeValueAsString(Map.of("itemIds", List.of(componentIds(order).get(0)), "packed", true)), viewerToken());

        assertThat(viewer.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void aShoppersOrderListsItsLinesInTheOrderTheyWereAddedToTheBasket() throws Exception {
        Fixture f = fixture();
        String session = "shopper-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), addBody(f.oilId(), 1, null), session);
        cartOk(post("/api/v1/cart/items"), addBody(f.packageVariantId(), 1, null), session);
        cartOk(post("/api/v1/cart/items"), addBody(f.cleanserId(), 1, null), session);
        // a later change to the first line must not move it
        cartOk(post("/api/v1/cart/items"), addBody(f.oilId(), 1, null), session);

        String body = "{\"customerName\":\"Test Customer\",\"guestEmail\":\"order-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com\",\"guestPhone\":\"" + phone() + "\",\"fulfilmentType\":\"PICKUP\"}";
        String id = cartOk(post("/api/v1/checkout"), body, session).get("order").get("id").asText();

        JsonNode detail = ok(get("/api/v1/orders/" + id), null);
        assertThat(detail.get("items").get(0).get("qty").decimalValue()).as("the oil, twice, still first").isEqualByComparingTo("2");
        assertThat(detail.get("items").get(1).get("components")).as("then the package with what is inside").hasSize(2);
        assertThat(detail.get("items").get(2).get("variantId").asText()).as("then the cleanser").isEqualTo(f.cleanserId());
    }
}
