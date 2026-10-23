package com.nbh.backend.finance;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * How much the website has made over a period, every payment with the path it took, the life of one order's money, and
 * the automatic history of the shop's own selling prices (owner, 2026-10-23). Each test works in a period of its own: it
 * notes the moment it starts, does its work, and asks about that period only, so the payments other tests made earlier
 * in the same database are outside it. Same Spring context as the other integration tests (identical annotations).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinanceIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;
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

    // ---- helpers ----

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body, String bearer) throws Exception {
        request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode okAs(MockHttpServletRequestBuilder request, String body, String bearer) throws Exception {
        MvcResult result = call(request, body, bearer);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        return okAs(request, body, token);
    }

    private JsonNode cartOk(MockHttpServletRequestBuilder request, String body, String session) throws Exception {
        request.header("X-Cart-Session", session);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private StaffUser newStaff(String roleName) {
        StaffUser user = new StaffUser();
        user.setEmail(roleName.toLowerCase().replace('_', '-') + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode("irrelevant-password"));
        user.setFirstName(roleName.charAt(0) + roleName.substring(1).toLowerCase().replace('_', ' '));
        user.setLastName("Finance " + UUID.randomUUID().toString().substring(0, 4));
        user.setStaffRole(staffRoles.findByName(roleName).orElseThrow());
        return staffUsers.save(user);
    }

    /** A published product with one option at this price and plenty on the shelf; returns its variant id. */
    private String newProduct(String price) throws Exception {
        JsonNode product = ok(post("/api/v1/products/draft"), "{\"name\":\"" + unique("Finance Product")
                + "\",\"categoryIds\":[],\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\""
                + price + "\",\"isActive\":true}]}");
        ok(post("/api/v1/products/" + product.get("id").asText() + "/publish"), null);
        String variantId = product.get("variants").get(0).get("id").asText();
        ok(put("/api/v1/products/" + product.get("id").asText() + "/variants/" + variantId + "/stock"),
                "{\"quantity\":50,\"unitCost\":100}");
        return variantId;
    }

    private String basket(String variantId, int qty) throws Exception {
        String session = "staff-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), "{\"variantId\":\"" + variantId + "\",\"qty\":" + qty + "}", session);
        return session;
    }

    private static String phone() {
        return "07" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    private static String code() {
        return "QW" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    /** A staff order, placed with this token; {@code payNow} (or null for pay later). */
    private JsonNode staffOrder(String variantId, int qty, String subtotal, String customer, Map<String, Object> payNow,
                                List<Map<String, Object>> priceChanges, String bearer) throws Exception {
        String session = basket(variantId, qty);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cartSession", session);
        body.put("customerName", customer);
        body.put("phone", phone());
        body.put("fulfilmentType", "PICKUP");
        body.put("deliveryAddress", "Simara Mall — Shop B33");
        body.put("expectedSubtotal", subtotal);
        if (payNow != null) body.put("payNow", payNow);
        if (priceChanges != null) {
            // the screen names the basket's line by its id
            JsonNode cart = cartOk(get("/api/v1/cart"), null, session);
            List<Map<String, Object>> changes = new ArrayList<>();
            for (Map<String, Object> change : priceChanges) {
                Map<String, Object> withLine = new LinkedHashMap<>(change);
                withLine.put("cartItemId", cart.get("items").get(0).get("id").asText());
                changes.add(withLine);
            }
            body.put("priceChanges", changes);
        }
        return okAs(post("/api/v1/admin/orders"), json.writeValueAsString(body), bearer).get("order");
    }

    private JsonNode staffOrder(String variantId, int qty, String subtotal, String customer, Map<String, Object> payNow)
            throws Exception {
        return staffOrder(variantId, qty, subtotal, customer, payNow, null, token);
    }

    /** An order the customer placed on the website, confirmed by the owner with this reference. */
    private JsonNode shopperOrderPaid(String variantId, String method, String reference) throws Exception {
        String session = "shopper-" + UUID.randomUUID();
        cartOk(post("/api/v1/cart/items"), "{\"variantId\":\"" + variantId + "\",\"qty\":1}", session);
        String body = "{\"customerName\":\"Website Customer\",\"guestEmail\":\"fin-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com\",\"guestPhone\":\"" + phone() + "\",\"fulfilmentType\":\"PICKUP\",\"deliveryAddress\":\"Junction — Stalls D5\"}";
        String orderId = cartOk(post("/api/v1/checkout"), body, session).get("order").get("id").asText();
        JsonNode detail = ok(get("/api/v1/orders/" + orderId), null);
        ok(post("/api/v1/payments/" + detail.get("payments").get(0).get("id").asText() + "/confirm-manual"),
                json.writeValueAsString(Map.of("reference", reference, "notes", "Checked the statement", "method", method)));
        return ok(get("/api/v1/orders/" + orderId), null);
    }

    private static String period(Instant from) {
        return "from=" + from + "&to=" + Instant.now().plusSeconds(300);
    }

    private JsonNode finance(String path, Instant from) throws Exception {
        return ok(get("/api/v1/admin/finance/" + path + (path.contains("?") ? "&" : "?") + period(from)), null);
    }

    private static BigDecimal dec(JsonNode node) {
        return node.decimalValue();
    }

    private static JsonNode byMethod(JsonNode summary, String method) {
        for (JsonNode m : summary.get("received").get("byMethod")) {
            if (method.equals(m.get("method").asText())) return m;
        }
        return null;
    }

    // ---- the summary ----

    @Test
    void theSummaryAddsUpWhatCameInWhoPlacedItAndWhatWasLost() throws Exception {
        String oil = newProduct("450");
        String bigger = newProduct("1000");
        Instant from = Instant.now();

        staffOrder(oil, 2, "900", "Walk-in One", Map.of("method", "CASH"));
        // 450 changed to 400 by the owner, who needs nobody's OK, paid by M-PESA
        staffOrder(oil, 1, "400", "Walk-in Two", Map.of("method", "MPESA", "reference", code()), List.of(
                Map.of("newUnitPrice", "400", "reason", "Opening week")), token);
        shopperOrderPaid(bigger, "BANK", code());
        // an order placed and then cancelled with no money, and one still waiting
        JsonNode cancelled = staffOrder(oil, 1, "450", "Changed Mind", null);
        ok(post("/api/v1/orders/" + cancelled.get("id").asText() + "/cancel"), "{\"reason\":\"test\"}");
        staffOrder(oil, 1, "450", "Still Deciding", null);

        JsonNode summary = finance("summary", from);

        assertThat(dec(summary.get("received").get("total"))).as("900 + 400 + 1000").isEqualByComparingTo("2300");
        assertThat(summary.get("received").get("orders").asInt()).isEqualTo(3);
        assertThat(dec(byMethod(summary, "CASH").get("amount"))).isEqualByComparingTo("900");
        assertThat(dec(byMethod(summary, "MPESA").get("amount"))).isEqualByComparingTo("400");
        assertThat(dec(byMethod(summary, "BANK").get("amount"))).isEqualByComparingTo("1000");
        assertThat(byMethod(summary, "BANK").get("payments").asInt()).isEqualTo(1);
        assertThat(dec(summary.get("sales").get("products"))).isEqualByComparingTo("2300");
        assertThat(dec(summary.get("sales").get("delivery"))).isEqualByComparingTo("0");
        assertThat(summary.get("split").get("staffEntered").get("orders").asInt()).isEqualTo(2);
        assertThat(dec(summary.get("split").get("staffEntered").get("received"))).isEqualByComparingTo("1300");
        assertThat(summary.get("split").get("customerPlaced").get("orders").asInt()).isEqualTo(1);
        assertThat(dec(summary.get("split").get("customerPlaced").get("received"))).isEqualByComparingTo("1000");
        assertThat(dec(summary.get("received").get("ofWhichCancelled"))).isEqualByComparingTo("0");

        assertThat(summary.get("waiting").get("orders").asInt()).as("at least the one still waiting").isGreaterThanOrEqualTo(1);
        JsonNode lost = summary.get("notCompleted");
        assertThat(lost).hasSize(1);
        assertThat(lost.get(0).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(dec(lost.get(0).get("amount"))).isEqualByComparingTo("450");

        assertThat(summary.get("priceChanges").get("changes").asInt()).isEqualTo(1);
        assertThat(dec(summary.get("priceChanges").get("givenAway"))).isEqualByComparingTo("50");
        assertThat(dec(summary.get("priceChanges").get("raised"))).isEqualByComparingTo("0");

        BigDecimal perDay = BigDecimal.ZERO;
        BigDecimal staffPerDay = BigDecimal.ZERO;
        for (JsonNode day : summary.get("daily")) {
            perDay = perDay.add(dec(day.get("received")));
            staffPerDay = staffPerDay.add(dec(day.get("staffEntered")));
        }
        assertThat(perDay).as("the days add up to the total").isEqualByComparingTo("2300");
        assertThat(staffPerDay).isEqualByComparingTo("1300");
    }

    @Test
    void moneyTakenForAnOrderThatWasThenCancelledIsFlaggedSoARefundIsNotForgotten() throws Exception {
        String oil = newProduct("450");
        Instant from = Instant.now();
        JsonNode order = staffOrder(oil, 1, "450", "Paid Then Cancelled", Map.of("method", "CASH"));
        ok(post("/api/v1/orders/" + order.get("id").asText() + "/cancel"), "{\"reason\":\"customer changed their mind\"}");

        JsonNode summary = finance("summary", from);

        assertThat(dec(summary.get("received").get("total"))).as("the money did come in").isEqualByComparingTo("450");
        assertThat(dec(summary.get("received").get("ofWhichCancelled"))).isEqualByComparingTo("450");

        JsonNode trail = ok(get("/api/v1/admin/finance/orders/" + order.get("id").asText() + "/trail"), null);
        List<String> kinds = new ArrayList<>();
        trail.get("steps").forEach(s -> kinds.add(s.get("kind").asText()));
        assertThat(kinds).contains("ORDER_CLOSED");
        JsonNode closed = null;
        for (JsonNode s : trail.get("steps")) if ("ORDER_CLOSED".equals(s.get("kind").asText())) closed = s;
        assertThat(closed.get("detail").asText()).contains("check whether it was refunded");
    }

    // ---- the ledger ----

    @Test
    void theLedgerShowsEachPaymentWithHowItWasConfirmedAndCanBeFiltered() throws Exception {
        String oil = newProduct("450");
        String bigger = newProduct("1000");
        Instant from = Instant.now();
        String mpesaCode = code();
        JsonNode cash = staffOrder(oil, 2, "900", "Ledger Cash", Map.of("method", "CASH"));
        JsonNode mpesa = staffOrder(oil, 1, "450", "Ledger Mpesa", Map.of("method", "MPESA", "reference", mpesaCode));
        String bankCode = code();
        JsonNode shopper = shopperOrderPaid(bigger, "BANK", bankCode);

        JsonNode all = finance("payments", from);
        assertThat(all.get("totalElements").asInt()).isEqualTo(3);
        Map<String, JsonNode> byOrder = new HashMap<>();
        all.get("content").forEach(e -> byOrder.put(e.get("orderNo").asText(), e));

        JsonNode cashRow = byOrder.get(cash.get("orderNo").asText());
        assertThat(cashRow.get("method").asText()).isEqualTo("CASH");
        assertThat(dec(cashRow.get("amount"))).isEqualByComparingTo("900");
        assertThat(cashRow.get("placedBy").asText()).isEqualTo(admin.getFullName());
        assertThat(cashRow.get("confirmedHow").asText()).isEqualTo("Taken by staff with the order");
        assertThat(cashRow.get("confirmedBy").asText()).isEqualTo(admin.getFullName());
        assertThat(cashRow.get("customerName").asText()).isEqualTo("Ledger Cash");

        JsonNode mpesaRow = byOrder.get(mpesa.get("orderNo").asText());
        assertThat(mpesaRow.get("reference").asText()).isEqualTo(mpesaCode);

        JsonNode shopperRow = byOrder.get(shopper.get("orderNo").asText());
        assertThat(shopperRow.get("placedBy").isNull()).as("placed by the customer, not by staff").isTrue();
        assertThat(shopperRow.get("confirmedHow").asText()).isEqualTo("Confirmed by staff");
        assertThat(shopperRow.get("confirmedBy").asText()).as("who confirmed it is kept with the payment").isEqualTo(admin.getFullName());
        assertThat(shopperRow.get("reference").asText()).isEqualTo(bankCode);
        assertThat(shopperRow.get("method").asText()).isEqualTo("BANK");

        // newest first
        assertThat(all.get("content").get(0).get("orderNo").asText()).isEqualTo(shopper.get("orderNo").asText());

        assertThat(finance("payments?method=BANK", from).get("totalElements").asInt()).isEqualTo(1);
        assertThat(finance("payments?method=cash", from).get("totalElements").asInt()).as("any case").isEqualTo(1);
        assertThat(finance("payments?source=STAFF", from).get("totalElements").asInt()).isEqualTo(2);
        assertThat(finance("payments?source=CUSTOMER", from).get("totalElements").asInt()).isEqualTo(1);
        assertThat(finance("payments?q=" + cash.get("orderNo").asText(), from).get("totalElements").asInt()).isEqualTo(1);
        assertThat(finance("payments?q=" + mpesaCode.toLowerCase(), from).get("totalElements").asInt()).as("a reference").isEqualTo(1);
        assertThat(finance("payments?q=ledger mpesa", from).get("totalElements").asInt()).as("a name").isEqualTo(1);
        assertThat(finance("payments?q=nothing-like-this", from).get("totalElements").asInt()).isZero();
        assertThat(finance("payments?size=2", from).get("content")).hasSize(2);
        assertThat(finance("payments?size=2&page=1", from).get("content")).hasSize(1);
    }

    @Test
    void aSearchForPercentSignsOrUnderscoresFindsThemAsLettersNotWildcards() throws Exception {
        String oil = newProduct("450");
        Instant from = Instant.now();
        staffOrder(oil, 1, "450", "Plain Name", Map.of("method", "CASH"));

        assertThat(finance("payments?q=%", from).get("totalElements").asInt()).as("%").isZero();
        assertThat(finance("payments?q=_", from).get("totalElements").asInt()).as("_").isZero();
    }

    @Test
    void theLedgerCanBeDownloadedAsAFileAndNoNameCanRunAsAFormula() throws Exception {
        String oil = newProduct("450");
        Instant from = Instant.now();
        staffOrder(oil, 1, "450", "=HYPERLINK(\"http://example.com\",\"click\")", Map.of("method", "CASH"));
        staffOrder(oil, 1, "450", "Comma, Name", Map.of("method", "CASH"));

        MvcResult result = call(get("/api/v1/admin/finance/payments.csv?" + period(from)), null, token);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("attachment").contains(".csv");
        String csv = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Paid at (Nairobi),Order,Customer,Phone,Way of payment,Amount (KSh)");
        String[] lines = csv.split("\r\n");
        assertThat(lines).as("a header and a line for each payment").hasSize(3);
        assertThat(csv).as("a name that starts like a formula gets a quote in front, and its own quotes are doubled")
                .contains("\"'=HYPERLINK(\"\"http://example.com\"\",\"\"click\"\")\"");
        assertThat(csv).as("a name with a comma is quoted").contains("\"Comma, Name\"");
        assertThat(csv).contains("Taken by staff with the order").contains(admin.getFullName());
    }

    // ---- one order's money ----

    @Test
    void aTrailFollowsOneOrdersMoneyFromThePlacingToThePaymentAndNamesWhoConfirmed() throws Exception {
        String oil = newProduct("450");
        StaffUser cashier = newStaff("CASHIER");
        StaffUser manager = newStaff("MANAGER");
        String cashierToken = jwtService.generateAccessToken(cashier);
        // the cashier changes the price, naming the manager who agreed it; the customer pays later
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("newUnitPrice", "400");
        change.put("reason", "Regular customer");
        change.put("confirmerId", manager.getId().toString());
        change.put("whatWasSaid", "Said 400 is fine");
        JsonNode order = staffOrder(oil, 2, "800", "Trail Customer", null, List.of(change), cashierToken);
        String orderId = order.get("id").asText();

        JsonNode before = ok(get("/api/v1/admin/finance/orders/" + orderId + "/trail"), null);
        assertThat(before.get("orderNo").asText()).isEqualTo(order.get("orderNo").asText());
        assertThat(dec(before.get("grandTotal"))).isEqualByComparingTo("800");
        assertThat(dec(before.get("received"))).isEqualByComparingTo("0");
        assertThat(dec(before.get("stillOwed"))).as("nothing paid yet").isEqualByComparingTo("800");
        assertThat(before.get("placedBy").asText()).isEqualTo(cashier.getFullName());

        // the manager answers the price change, the owner confirms the payment
        String changeId = order.get("priceChanges").get(0).get("id").asText();
        okAs(post("/api/v1/admin/price-changes/" + changeId + "/confirm"), "{\"note\":\"Yes\"}", jwtService.generateAccessToken(manager));
        String paymentId = ok(get("/api/v1/orders/" + orderId), null).get("payments").get(0).get("id").asText();
        String bankReference = code();
        ok(post("/api/v1/payments/" + paymentId + "/confirm-manual"),
                json.writeValueAsString(Map.of("reference", bankReference, "notes", "On the statement", "method", "BANK")));

        // and a customer code that was approved and a bank credit matched to the payment, as the payment screens record them
        UUID claimId = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_claim (id, payment_record_id, transaction_code, amount_claimed, payer_name, status,"
                        + " resolved_by, resolved_at, resolution_note) VALUES (?::uuid, ?::uuid, ?, 800, 'T. Customer', 'APPROVED',"
                        + " 'owner@example.com', now(), 'Matches the statement')",
                claimId.toString(), paymentId, "CLAIM" + bankReference);
        jdbc.update("INSERT INTO bank_credit (id, bank_txn_id, amount, payer_name, narration, received_at, source, status,"
                        + " payment_record_id, resolved_by, resolved_at, resolution_note) VALUES (?::uuid, ?, 800, 'T CUSTOMER',"
                        + " 'Pay for order', now(), 'MANUAL', 'MATCHED', ?::uuid, 'system', now(), 'Matched automatically')",
                UUID.randomUUID().toString(), "TXN" + bankReference, paymentId);

        JsonNode trail = ok(get("/api/v1/admin/finance/orders/" + orderId + "/trail"), null);
        List<String> kinds = new ArrayList<>();
        trail.get("steps").forEach(s -> kinds.add(s.get("kind").asText()));
        assertThat(kinds).contains("ORDER_PLACED", "PRICE_CHANGE", "PRICE_CHANGE_ANSWER", "PAYMENT_ASKED", "CUSTOMER_CODE",
                "CODE_ANSWER", "BANK_CREDIT", "BANK_MATCH", "PAYMENT_CONFIRMED");
        assertThat(kinds.get(0)).as("oldest first, the order itself").isEqualTo("ORDER_PLACED");
        assertThat(dec(trail.get("received"))).isEqualByComparingTo("800");
        assertThat(dec(trail.get("stillOwed"))).isEqualByComparingTo("0");

        JsonNode placed = trail.get("steps").get(0);
        assertThat(placed.get("title").asText()).isEqualTo("Order entered by " + cashier.getFullName());
        assertThat(placed.get("detail").asText()).contains("KSh 800");
        for (JsonNode step : trail.get("steps")) {
            String kind = step.get("kind").asText();
            String title = step.get("title").asText();
            String detail = step.get("detail").isNull() ? "" : step.get("detail").asText();
            if ("PRICE_CHANGE".equals(kind)) {
                assertThat(title).contains("KSh 450 → KSh 400 each").contains(" × 2");
                assertThat(detail).contains("Regular customer").contains(manager.getFullName()).contains("Said 400 is fine");
            }
            if ("PRICE_CHANGE_ANSWER".equals(kind)) assertThat(title).contains(manager.getFullName() + " confirmed");
            if ("CUSTOMER_CODE".equals(kind)) assertThat(title).contains("CLAIM" + bankReference).contains("KSh 800");
            if ("CODE_ANSWER".equals(kind)) assertThat(title).contains("owner@example.com approved");
            if ("BANK_CREDIT".equals(kind)) assertThat(title).contains("KSh 800").contains("T CUSTOMER");
            if ("BANK_MATCH".equals(kind)) assertThat(title).contains("Matched automatically");
            if ("PAYMENT_CONFIRMED".equals(kind)) {
                assertThat(title).contains("bank transfer").contains("KSh 800").contains(bankReference);
                assertThat(detail).contains("Confirmed by " + admin.getFullName()).contains("On the statement");
            }
        }
    }

    @Test
    void aTrailForAnUnknownOrderIsNotFound() throws Exception {
        assertThat(call(get("/api/v1/admin/finance/orders/" + UUID.randomUUID() + "/trail"), null, token).getResponse().getStatus())
                .isEqualTo(404);
    }

    // ---- who may read it, and what a period must be ----

    @Test
    void onlyThePeopleWhoOverseeTheMoneyCanReadIt() throws Exception {
        Instant from = Instant.now().minusSeconds(60);
        String path = "/api/v1/admin/finance/summary?" + period(from);
        // the owner, a manager and the read-only reporting account may; a cashier, a stock controller and a stranger may not
        assertThat(call(get(path), null, jwtService.generateAccessToken(newStaff("MANAGER"))).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get(path), null, jwtService.generateAccessToken(newStaff("VIEWER"))).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get(path), null, jwtService.generateAccessToken(newStaff("CASHIER"))).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get(path), null, jwtService.generateAccessToken(newStaff("STOCK_CONTROLLER"))).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).isIn(401, 403);

        String viewer = jwtService.generateAccessToken(newStaff("VIEWER"));
        assertThat(call(get("/api/v1/admin/finance/payments?" + period(from)), null, viewer).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get("/api/v1/admin/finance/payments.csv?" + period(from)), null, viewer).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get("/api/v1/admin/finance/price-history?" + period(from)), null, viewer).getResponse().getStatus()).isEqualTo(200);
        String cashier = jwtService.generateAccessToken(newStaff("CASHIER"));
        assertThat(call(get("/api/v1/admin/finance/payments?" + period(from)), null, cashier).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get("/api/v1/admin/finance/payments.csv?" + period(from)), null, cashier).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get("/api/v1/admin/finance/orders/" + UUID.randomUUID() + "/trail"), null, cashier).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void aPeriodHasToMakeSenseAndTheFiltersToBeKnown() throws Exception {
        Instant now = Instant.now();
        assertThat(call(get("/api/v1/admin/finance/summary?from=" + now + "&to=" + now.minusSeconds(60)), null, token)
                .getResponse().getStatus()).as("it must start before it ends").isEqualTo(422);
        assertThat(call(get("/api/v1/admin/finance/summary?from=" + now.minusSeconds(401L * 86400) + "&to=" + now), null, token)
                .getResponse().getStatus()).as("no more than 400 days").isEqualTo(422);
        assertThat(call(get("/api/v1/admin/finance/payments?method=GOLD&" + period(now.minusSeconds(60))), null, token)
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(call(get("/api/v1/admin/finance/payments?source=MARS&" + period(now.minusSeconds(60))), null, token)
                .getResponse().getStatus()).isEqualTo(422);
        // with nothing given, the last 30 days
        JsonNode lastMonth = ok(get("/api/v1/admin/finance/summary"), null);
        assertThat(Instant.parse(lastMonth.get("to").asText()).getEpochSecond() - Instant.parse(lastMonth.get("from").asText()).getEpochSecond())
                .isEqualTo(30L * 86400);
    }

    // ---- the shop's own prices ----

    @Test
    void everyChangeOfASellingPriceIsKeptWithWhoAndWhenWithoutAskingForAReason() throws Exception {
        Instant from = Instant.now();
        JsonNode product = ok(post("/api/v1/products/draft"), "{\"name\":\"" + unique("History Product")
                + "\",\"categoryIds\":[],\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"100\",\"isActive\":true}]}");
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();

        // saving it again with the same price is not a change
        ok(put("/api/v1/products/" + productId + "/variants/" + variantId),
                "{\"id\":\"" + variantId + "\",\"variantName\":\"Default\",\"sellingPrice\":\"100\",\"isActive\":true}");
        assertThat(finance("price-history?q=" + product.get("name").asText(), from).get("totalElements").asInt()).isZero();

        // the editor saves a new price
        ok(put("/api/v1/products/" + productId + "/variants/" + variantId),
                "{\"id\":\"" + variantId + "\",\"variantName\":\"Default\",\"sellingPrice\":\"120\",\"isActive\":true}");
        // and a bulk change of -50% takes it to 60
        ok(post("/api/v1/products/bulk"), "{\"action\":\"ADJUST_PRICE\",\"productIds\":[\"" + productId + "\"],\"percent\":-50}");

        JsonNode history = finance("price-history?q=" + product.get("name").asText(), from);
        assertThat(history.get("totalElements").asInt()).isEqualTo(2);
        JsonNode newest = history.get("content").get(0);
        assertThat(dec(newest.get("oldPrice"))).isEqualByComparingTo("120");
        assertThat(dec(newest.get("newPrice"))).isEqualByComparingTo("60");
        assertThat(newest.get("changedBy").asText()).isEqualTo(admin.getFullName());
        assertThat(newest.get("productName").asText()).isEqualTo(product.get("name").asText());
        assertThat(newest.get("variantId").asText()).isEqualTo(variantId);
        assertThat(Instant.parse(newest.get("changedAt").asText())).isAfterOrEqualTo(from);
        JsonNode first = history.get("content").get(1);
        assertThat(dec(first.get("oldPrice"))).isEqualByComparingTo("100");
        assertThat(dec(first.get("newPrice"))).isEqualByComparingTo("120");
        assertThat(first.get("changedBy").asText()).isEqualTo(admin.getFullName());

        // a search that matches nothing finds nothing, and the SKU finds it
        assertThat(finance("price-history?q=nothing-like-this", from).get("totalElements").asInt()).isZero();
        String sku = jdbc.queryForObject("SELECT sku FROM product_variant WHERE id = ?::uuid", String.class, variantId);
        assertThat(finance("price-history?q=" + sku.toLowerCase(), from).get("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void aPriceChangedBehindTheApplicationsBackIsKeptWithNoNameNotTheLastEditorsName() throws Exception {
        Instant from = Instant.now();
        JsonNode product = ok(post("/api/v1/products/draft"), "{\"name\":\"" + unique("Script Product")
                + "\",\"categoryIds\":[],\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"100\",\"isActive\":true}]}");
        String variantId = product.get("variants").get(0).get("id").asText();
        // the owner edited something about this variant a moment ago, so the row carries their name
        ok(put("/api/v1/products/" + product.get("id").asText() + "/variants/" + variantId),
                "{\"id\":\"" + variantId + "\",\"variantName\":\"Default\",\"description\":\"Edited\",\"sellingPrice\":\"100\",\"isActive\":true}");
        assertThat(jdbc.queryForObject("SELECT updated_by FROM product_variant WHERE id = ?::uuid", String.class, variantId))
                .isEqualTo(admin.getFullName());

        jdbc.update("UPDATE product_variant SET selling_price = 90 WHERE id = ?::uuid", variantId);

        JsonNode history = finance("price-history?q=" + product.get("name").asText(), from);
        assertThat(history.get("totalElements").asInt()).isEqualTo(1);
        assertThat(dec(history.get("content").get(0).get("newPrice"))).isEqualByComparingTo("90");
        assertThat(history.get("content").get(0).get("changedBy").isNull())
                .as("the record does not say, rather than naming whoever last edited something else").isTrue();
    }
}
