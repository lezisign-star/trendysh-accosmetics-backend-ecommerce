package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.service.OrderExpirySweeper;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockReservationRepository;
import com.nbh.backend.stock.service.StockReservationService;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Checkout gaps found by the inventory plan's checkout review (2026-09-28), against real Postgres:
 * a payment confirmed by staff after the stock hold has lapsed, unpaid orders that never expired,
 * the per-contact open-order cap, and claims extending a hold more than once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LatePaymentAndExpiryIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StockReservationRepository reservationRepository;
    @Autowired private StockReservationService reservationService;
    @Autowired private StockBalanceRepository balanceRepository;
    @Autowired private OrderExpirySweeper orderExpirySweeper;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String staffToken;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    private record Item(String productId, String variantId) {}

    private record Placed(String orderId, String orderNo, String paymentId, String reference) {}

    private JsonNode okStaff(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.header("Authorization", "Bearer " + staffToken)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private MvcResult staff(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + staffToken)).andReturn();
    }

    private Item newPublishedVariantWithStock(int qty) throws Exception {
        String name = "Late Pay Test " + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"name\":\"" + name + "\",\"categoryIds\":[],\"published\":false,"
                + "\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        setStock(new Item(productId, variantId), qty);
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return new Item(productId, variantId);
    }

    private void setStock(Item item, int qty) throws Exception {
        okStaff(put("/api/v1/products/" + item.productId() + "/variants/" + item.variantId() + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + qty + ",\"unitCost\":100}"));
    }

    private MvcResult checkout(Item item, int qty, String phone, String email) throws Exception {
        String sessionId = UUID.randomUUID().toString();
        MvcResult cart = mvc.perform(post("/api/v1/cart/items")
                        .header("X-Cart-Session", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + item.variantId() + "\",\"qty\":" + qty + "}"))
                .andReturn();
        assertThat(cart.getResponse().getStatus()).as(cart.getResponse().getContentAsString()).isEqualTo(200);
        String body = """
                {"customerName":"Test Customer","guestEmail":"%s","guestPhone":"%s","fulfilmentType":"PICKUP"}
                """.formatted(email, phone);
        return mvc.perform(post("/api/v1/checkout")
                        .header("X-Cart-Session", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private Placed place(Item item, int qty) throws Exception {
        return place(item, qty, TestContacts.phone(), TestContacts.email());
    }

    private Placed place(Item item, int qty, String phone, String email) throws Exception {
        MvcResult result = checkout(item, qty, phone, email);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return new Placed(body.get("order").get("id").asText(), body.get("order").get("orderNo").asText(),
                body.get("paymentRecordId").asText(), body.get("paymentInstructions").get("reference").asText());
    }

    private List<StockReservation> holdsOf(Placed placed) {
        UUID orderId = UUID.fromString(placed.orderId());
        return reservationRepository.findAll().stream()
                .filter(r -> "CUSTOMER_ORDER".equals(r.getReferenceType()) && orderId.equals(r.getReferenceId()))
                .toList();
    }

    /**
     * What the expiry sweeper does when the payment window ends: the hold's expiry has passed, then it is
     * expired. (Expiring a hold whose expiry has NOT passed is refused — it may have been paid or extended.)
     */
    private void lapseTheHold(Placed placed) {
        holdsOf(placed).stream()
                .filter(r -> r.getStatus() == StockReservation.ReservationStatus.ACTIVE)
                .forEach(r -> {
                    jdbc.update("UPDATE stock_reservation SET expires_at = now() - interval '1 minute' WHERE id = ?", r.getId());
                    reservationService.expire(r.getId());
                });
    }

    private String orderStatus(Placed placed) throws Exception {
        return okStaff(get("/api/v1/orders/" + placed.orderId())).get("status").asText();
    }

    private MvcResult confirm(Placed placed) throws Exception {
        return staff(post("/api/v1/payments/" + placed.paymentId() + "/confirm-manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"LATE" + UUID.randomUUID().toString().substring(0, 8) + "\",\"method\":\"MPESA\"}"));
    }

    @Test
    void aPaymentConfirmedAfterTheHoldLapsedRetakesTheStockWhenItIsStillFree() throws Exception {
        Item item = newPublishedVariantWithStock(10);
        Placed placed = place(item, 2);
        lapseTheHold(placed);

        assertThat(orderExpirySweeper.sweepUnpaidOrders()).isGreaterThanOrEqualTo(1);
        assertThat(orderStatus(placed)).isEqualTo("EXPIRED");

        assertThat(confirm(placed).getResponse().getStatus()).isEqualTo(200);

        assertThat(orderStatus(placed)).isEqualTo("PAID");
        List<StockReservation> active = holdsOf(placed).stream()
                .filter(r -> r.getStatus() == StockReservation.ReservationStatus.ACTIVE).toList();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getSource()).isEqualTo(StockReservation.ReservationSource.ONLINE_ORDER);
        assertThat(active.get(0).getExpiresAt()).isNull();
        assertThat(balanceRepository.findByVariantIdAndAccount(UUID.fromString(item.variantId()), StockAccount.SHOP)
                .orElseThrow().getReservedQty()).isEqualByComparingTo("2");
    }

    @Test
    void aLatePaymentIsRefusedClearlyWhenTheStockHasGoneAndWorksOnceItIsRestocked() throws Exception {
        Item item = newPublishedVariantWithStock(5);
        Placed placed = place(item, 2);
        lapseTheHold(placed);
        okStaff(post("/api/v1/stock/write-offs").contentType(MediaType.APPLICATION_JSON).content("""
                {"lines":[{"variantId":"%s","qty":"5"}],"reasonCode":"DAMAGED","notes":"gone"}
                """.formatted(item.variantId())));

        MvcResult refused = confirm(placed);

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString())
                .contains("stock has since gone").contains("refund the customer");
        assertThat(orderStatus(placed)).isNotEqualTo("PAID");

        setStock(item, 5);
        assertThat(confirm(placed).getResponse().getStatus()).isEqualTo(200);
        assertThat(orderStatus(placed)).isEqualTo("PAID");
    }

    private MvcResult confirmWith(Placed placed, String code) throws Exception {
        return staff(post("/api/v1/payments/" + placed.paymentId() + "/confirm-manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + code + "\",\"method\":\"MPESA\"}"));
    }

    private void markFailed(Placed placed) throws Exception {
        okStaff(post("/api/v1/payments/" + placed.paymentId() + "/fail-manual")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no payment seen\"}"));
    }

    private java.math.BigDecimal reserved(Item item) {
        return balanceRepository.findByVariantIdAndAccount(UUID.fromString(item.variantId()), StockAccount.SHOP)
                .orElseThrow().getReservedQty();
    }

    /**
     * Found in the 2026-10-06 live QA: staff who marked a payment failed by mistake could not put it right when the
     * customer then showed the M-PESA message ("cannot confirm success from this state"), so money was received for an
     * order that was dead. Confirming it now brings the order back and holds the stock again.
     */
    @Test
    void aPaymentMarkedFailedByMistakeCanBeConfirmedAndTheStockIsHeldAgain() throws Exception {
        Item item = newPublishedVariantWithStock(10);
        Placed placed = place(item, 2);
        markFailed(placed);
        assertThat(orderStatus(placed)).isEqualTo("PAYMENT_FAILED");
        assertThat(reserved(item)).isEqualByComparingTo("0");

        assertThat(confirm(placed).getResponse().getStatus()).isEqualTo(200);

        assertThat(orderStatus(placed)).isEqualTo("PAID");
        assertThat(reserved(item)).isEqualByComparingTo("2");
        List<StockReservation> active = holdsOf(placed).stream()
                .filter(r -> r.getStatus() == StockReservation.ReservationStatus.ACTIVE).toList();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getExpiresAt()).as("a paid order's hold is permanent").isNull();
    }

    @Test
    void revivingAFailedPaymentIsRefusedClearlyWhenTheStockHasGoneAndWorksOnceItIsRestocked() throws Exception {
        Item item = newPublishedVariantWithStock(5);
        Placed placed = place(item, 2);
        markFailed(placed);
        okStaff(post("/api/v1/stock/write-offs").contentType(MediaType.APPLICATION_JSON).content("""
                {"lines":[{"variantId":"%s","qty":"5"}],"reasonCode":"DAMAGED","notes":"gone"}
                """.formatted(item.variantId())));

        MvcResult refused = confirm(placed);

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("stock has since gone").contains("refund the customer");
        assertThat(orderStatus(placed)).isEqualTo("PAYMENT_FAILED");

        setStock(item, 5);
        assertThat(confirm(placed).getResponse().getStatus()).isEqualTo(200);
        assertThat(orderStatus(placed)).isEqualTo("PAID");
    }

    /**
     * An M-PESA code can pay for one order. Typed twice, the unique index used to answer with the generic
     * "A conflicting record already exists — please retry", which is untrue (retrying never helps) and does not say
     * which order already has the code.
     */
    @Test
    void aCodeAlreadyUsedOnAnotherOrderIsRefusedBySayingWhichOrderHasIt() throws Exception {
        Item item = newPublishedVariantWithStock(10);
        Placed first = place(item, 1);
        Placed second = place(item, 1);
        String code = "DUP" + UUID.randomUUID().toString().substring(0, 7).toUpperCase();

        assertThat(confirmWith(first, code).getResponse().getStatus()).isEqualTo(200);
        MvcResult refused = confirmWith(second, " " + code.toLowerCase() + " ");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString())
                .contains(first.orderNo()).contains("only pay for one order").doesNotContain("please retry");
        assertThat(orderStatus(second)).isEqualTo("PENDING_PAYMENT");

        assertThat(confirmWith(second, "OK" + code).getResponse().getStatus()).isEqualTo(200);
        assertThat(orderStatus(second)).isEqualTo("PAID");
    }

    /** Found in the 2026-10-06 live QA: "Cannot post: variant GELP-001001 would leave on_hand 1.000 below 2.000 still reserved by other holds". */
    @Test
    void settingTheCountBelowWhatOrdersHoldIsRefusedInPlainWords() throws Exception {
        Item item = newPublishedVariantWithStock(10);
        place(item, 3);

        MvcResult refused = staff(put("/api/v1/products/" + item.productId() + "/variants/" + item.variantId() + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1,\"unitCost\":100}"));

        assertThat(refused.getResponse().getStatus()).isIn(409, 422);
        String message = refused.getResponse().getContentAsString();
        assertThat(message).contains("3 of this item are held for customers' orders")
                .contains("the count can't go below 3").contains("it would go to 1")
                .doesNotContain("on_hand").doesNotContain("3.000");
    }

    @Test
    void aCancelledOrderCannotBeMarkedPaid() throws Exception {
        Item item = newPublishedVariantWithStock(5);
        Placed placed = place(item, 1);
        okStaff(post("/api/v1/orders/" + placed.orderId() + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"customer changed mind\"}"));

        MvcResult refused = confirm(placed);

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("cancelled");
        assertThat(orderStatus(placed)).isEqualTo("CANCELLED");
    }

    @Test
    void anUnpaidOrderStillHoldingStockIsLeftAlone() throws Exception {
        Item item = newPublishedVariantWithStock(5);
        Placed placed = place(item, 1);

        orderExpirySweeper.sweepUnpaidOrders();

        assertThat(orderStatus(placed)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void aContactAtTheOpenOrderLimitIsRefusedUntilOneOfTheirOrdersExpires() throws Exception {
        Item item = newPublishedVariantWithStock(20);
        String phone = TestContacts.phone();
        List<Placed> open = List.of(
                place(item, 1, phone, TestContacts.email()),
                place(item, 1, phone, TestContacts.email()),
                place(item, 1, phone, TestContacts.email()));

        MvcResult refused = checkout(item, 1, phone, TestContacts.email());
        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getContentAsString()).contains("waiting for payment");

        open.forEach(this::lapseTheHold);
        orderExpirySweeper.sweepUnpaidOrders();

        MvcResult allowed = checkout(item, 1, phone, TestContacts.email());
        assertThat(allowed.getResponse().getStatus()).as(allowed.getResponse().getContentAsString()).isEqualTo(200);
    }

    /**
     * The first sweep after a deploy moves every historical unpaid order to EXPIRED in one go, so the
     * awaiting queue must age an expired order from when its PAYMENT WINDOW ended, not from when the order
     * row was last written — otherwise every one of them lands back on the queue for a month.
     */
    @Test
    void theAwaitingQueueAgesAnExpiredOrderFromItsPaymentWindowNotFromTheSweep() throws Exception {
        Item item = newPublishedVariantWithStock(5);
        Placed placed = place(item, 1);
        lapseTheHold(placed);
        orderExpirySweeper.sweepUnpaidOrders();
        assertThat(orderStatus(placed)).isEqualTo("EXPIRED");

        jdbc.update("UPDATE payment_record SET expires_at = now() - interval '60 days' WHERE id = ?", UUID.fromString(placed.paymentId()));
        assertThat(awaitingOrderNos()).as("expired two months ago").doesNotContain(placed.orderNo());

        jdbc.update("UPDATE payment_record SET expires_at = now() - interval '1 hour' WHERE id = ?", UUID.fromString(placed.paymentId()));
        assertThat(awaitingOrderNos()).as("expired an hour ago").contains(placed.orderNo());
    }

    private List<String> awaitingOrderNos() throws Exception {
        JsonNode queue = okStaff(get("/api/v1/admin/payments/awaiting"));
        List<String> orderNos = new java.util.ArrayList<>();
        queue.forEach(entry -> orderNos.add(entry.get("orderNo").asText()));
        return orderNos;
    }

    /**
     * The sweeper lists lapsed holds and then expires them one at a time. A hold paid for in between
     * (its expiry cleared) must survive the sweeper's late arrival, or the paid order loses its stock.
     */
    @Test
    void aHoldPaidForAfterTheSweeperListedItIsNotExpiredFromUnderTheOrder() throws Exception {
        Item item = newPublishedVariantWithStock(5);
        Placed placed = place(item, 2);
        StockReservation hold = holdsOf(placed).get(0);
        jdbc.update("UPDATE stock_reservation SET expires_at = now() - interval '1 minute' WHERE id = ?", hold.getId());

        assertThat(confirm(placed).getResponse().getStatus()).isEqualTo(200); // paid while the hold is still ACTIVE
        reservationService.expire(hold.getId()); // ...and only now does the sweeper reach it

        assertThat(orderStatus(placed)).isEqualTo("PAID");
        assertThat(reservationRepository.findById(hold.getId()).orElseThrow().getStatus())
                .isEqualTo(StockReservation.ReservationStatus.ACTIVE);
        assertThat(balanceRepository.findByVariantIdAndAccount(UUID.fromString(item.variantId()), StockAccount.SHOP)
                .orElseThrow().getReservedQty()).isEqualByComparingTo("2");
    }

    @Test
    void onlyTheFirstPaidClaimExtendsTheHold() throws Exception {
        Item item = newPublishedVariantWithStock(5);
        Placed placed = place(item, 1);
        String claimPath = "/api/v1/payments/pay/" + placed.orderNo() + "/claim";

        mvc.perform(post(claimPath).param("ref", placed.reference()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionCode\":\"QA" + UUID.randomUUID().toString().substring(0, 8) + "\",\"amount\":1000}"))
                .andReturn();
        java.time.Instant afterFirst = holdsOf(placed).get(0).getExpiresAt();

        mvc.perform(post(claimPath).param("ref", placed.reference()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionCode\":\"QB" + UUID.randomUUID().toString().substring(0, 8) + "\",\"amount\":1000}"))
                .andReturn();
        java.time.Instant afterSecond = holdsOf(placed).get(0).getExpiresAt();

        // The first claim pushes the 45-minute hold out to about 12 hours; a second must not push it further.
        assertThat(afterFirst).isAfter(java.time.Instant.now().plus(java.time.Duration.ofHours(11)));
        assertThat(afterSecond).isEqualTo(afterFirst);
    }
}
