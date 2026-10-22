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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Orders that staff enter for a customer, and the pick-and-pack list on every order (owner, 2026-10-19). A staff order is
 * built from the shop's own basket under the staff member's session and placed through the same checkout, so it is priced,
 * kit-picked, stock-held and paid like a shopper's; what differs is who placed it. The pick list is the order's lines in
 * the order they were entered, a kit's products following it, each with a tick that is remembered. Same Spring context as
 * {@link com.nbh.backend.kits.KitIntegrationTest} (identical annotations), so no extra connection pool.
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

    // ---- helpers (the same ones the kit tests have, kept private there) ----

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
        if (picks != null) body.put("kitChoices", picks);
        return json.writeValueAsString(body);
    }

    /** Lashes in two colours (2 in the kit), a cleanser (1 in the kit) and a plain oil; the kit costs 1000 against 1300 apart. */
    private record Fixture(String kitVariantId, String redId, String pinkId, String cleanserId, String oilId) {}

    private Fixture fixture() throws Exception {
        Map<String, String> lashes = newProduct(unique("Staff Lashes"), new String[]{"Ruby Red", "500"}, new String[]{"Rose Pink", "650"});
        Map<String, String> cleanser = newProduct(unique("Staff Cleanser"), new String[]{"Default", "300"});
        Map<String, String> oil = newProduct(unique("Staff Oil"), new String[]{"Default", "450"});
        String red = lashes.get("Ruby Red"), pink = lashes.get("Rose Pink");
        String cleanserId = cleanser.get("Default"), oilId = oil.get("Default");
        receiveStock(Map.of(red, new BigDecimal("10"), pink, new BigDecimal("10"), cleanserId, new BigDecimal("10"),
                oilId, new BigDecimal("20")));
        Map<String, Object> kit = new LinkedHashMap<>();
        kit.put("name", unique("Staff Kit"));
        kit.put("professionId", newProfession());
        kit.put("categoryId", newCategory());
        kit.put("description", "For a technician");
        kit.put("finalPrice", "1000");
        kit.put("components", List.of(Map.of("variantId", red, "qty", "2"), Map.of("variantId", cleanserId, "qty", "1")));
        JsonNode created = ok(post("/api/v1/admin/kits"), json.writeValueAsString(kit));
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

    private String viewerToken() {
        StaffUser viewer = new StaffUser();
        viewer.setEmail("viewer-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        viewer.setPassword(passwordEncoder.encode("irrelevant-password"));
        viewer.setFirstName("Read");
        viewer.setLastName("Only");
        viewer.setStaffRole(staffRoles.findByName("VIEWER").orElseThrow());
        return jwtService.generateAccessToken(staffUsers.save(viewer));
    }

    // ---- entering an order ----

    @Test
    void aStaffOrderIsPricedAndHeldLikeAShoppersAndRemembersWhoEnteredIt() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId())), addBody(f.oilId(), 2, null));

        JsonNode placed = placeOk(orderBody(session, "2200", phone()));
        JsonNode order = placed.get("order");

        assertThat(order.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.get("subtotal").decimalValue()).as("1000 + 2 x (650 - 500) for the kit, and 2 x 450").isEqualByComparingTo("2200");
        assertThat(order.get("placedBy").asText()).isEqualTo(admin.getFullName());
        assertThat(placed.get("payRef").isNull()).as("the customer can pay later with the link").isFalse();
        assertThat(placed.get("paymentRecordId").asText()).isNotBlank();

        // the lines in the order they were entered: the kit, then the oil; the kit's own products in the kit's order
        JsonNode lines = order.get("items");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("1300");
        assertThat(lines.get(1).get("unitPrice").decimalValue()).isEqualByComparingTo("450");
        JsonNode inside = lines.get(0).get("components");
        assertThat(inside).hasSize(2);
        assertThat(inside.get(0).get("variantId").asText()).as("the colour that was picked, first in the kit").isEqualTo(f.pinkId());
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
        String session = basket(addBody(f.kitVariantId(), 1, null));
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

    @Test
    void onlyAManagerCanEnterAnOrder() throws Exception {
        Fixture f = fixture();
        String session = basket(addBody(f.oilId(), 1, null));

        MvcResult viewer = place(orderBody(session, "450", phone()), viewerToken());
        assertThat(viewer.getResponse().getStatus()).isEqualTo(403);
        assertThat(cartOk(get("/api/v1/cart"), null, session).get("items")).as("nothing was placed").hasSize(1);

        MvcResult anonymous = mvc.perform(post("/api/v1/admin/orders").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(orderBody(session, "450", phone())))).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isIn(401, 403);
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

    private JsonNode paidKitOrder(Fixture f) throws Exception {
        String session = basket(addBody(f.kitVariantId(), 1, Map.of(f.redId(), f.pinkId())), addBody(f.oilId(), 1, null));
        Map<String, Object> body = orderBody(session, "1750", phone());
        body.put("payNow", Map.of("method", "CASH"));
        return placeOk(body).get("order");
    }

    @Test
    void staffTickTheProductsOfThePickListAndTheTickIsRemembered() throws Exception {
        Fixture f = fixture();
        JsonNode order = paidKitOrder(f);
        String id = order.get("id").asText();
        List<String> pickable = componentIds(order);
        assertThat(pickable).as("the kit's two products and the oil").hasSize(3);

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
    void aKitsOwnLineIsNotPickedSoItCannotBeTicked() throws Exception {
        Fixture f = fixture();
        JsonNode order = paidKitOrder(f);
        String kitLine = order.get("items").get(0).get("itemId").asText();

        MvcResult refused = call(post("/api/v1/orders/" + order.get("id").asText() + "/packed"),
                json.writeValueAsString(Map.of("itemIds", List.of(kitLine), "packed", true)), token);

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("tick the products inside it");
    }

    @Test
    void aLineOfAnotherOrderOrAnEmptyListIsRefused() throws Exception {
        Fixture f = fixture();
        JsonNode order = paidKitOrder(f);
        JsonNode other = paidKitOrder(f);
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
        String session = basket(addBody(f.kitVariantId(), 1, null));
        JsonNode unpaid = placeOk(orderBody(session, "1000", phone())).get("order");
        MvcResult early = call(post("/api/v1/orders/" + unpaid.get("id").asText() + "/packed"),
                json.writeValueAsString(Map.of("itemIds", List.of(componentIds(unpaid).get(0)), "packed", true)), token);
        assertThat(early.getResponse().getStatus()).isEqualTo(422);

        // paid, then picking, then ready: still open
        JsonNode order = paidKitOrder(f);
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
    void theOrdersListCountsWhatWasBoughtNotTheProductsInsideAKit() throws Exception {
        Fixture f = fixture();
        JsonNode order = paidKitOrder(f);

        JsonNode list = ok(get("/api/v1/orders?q=" + order.get("orderNo").asText()), null);

        assertThat(list.get("content")).hasSize(1);
        assertThat(list.get("content").get(0).get("itemCount").asInt())
                .as("the kit and the oil: the kit's own two products are not counted again").isEqualTo(2);
    }

    @Test
    void onlyAManagerCanTick() throws Exception {
        Fixture f = fixture();
        JsonNode order = paidKitOrder(f);

        MvcResult viewer = call(post("/api/v1/orders/" + order.get("id").asText() + "/packed"),
                json.writeValueAsString(Map.of("itemIds", List.of(componentIds(order).get(0)), "packed", true)), viewerToken());

        assertThat(viewer.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void aShoppersOrderListsItsLinesInTheOrderTheyWereAddedToTheBasket() throws Exception {
        Fixture f = fixture();
        String session = "shopper-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), addBody(f.oilId(), 1, null), session);
        cartOk(post("/api/v1/cart/items"), addBody(f.kitVariantId(), 1, null), session);
        cartOk(post("/api/v1/cart/items"), addBody(f.cleanserId(), 1, null), session);
        // a later change to the first line must not move it
        cartOk(post("/api/v1/cart/items"), addBody(f.oilId(), 1, null), session);

        String body = "{\"customerName\":\"Test Customer\",\"guestEmail\":\"order-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com\",\"guestPhone\":\"" + phone() + "\",\"fulfilmentType\":\"PICKUP\"}";
        String id = cartOk(post("/api/v1/checkout"), body, session).get("order").get("id").asText();

        JsonNode detail = ok(get("/api/v1/orders/" + id), null);
        assertThat(detail.get("items").get(0).get("qty").decimalValue()).as("the oil, twice, still first").isEqualByComparingTo("2");
        assertThat(detail.get("items").get(1).get("components")).as("then the kit with what is inside").hasSize(2);
        assertThat(detail.get("items").get(2).get("variantId").asText()).as("then the cleanser").isEqualTo(f.cleanserId());
    }
}
