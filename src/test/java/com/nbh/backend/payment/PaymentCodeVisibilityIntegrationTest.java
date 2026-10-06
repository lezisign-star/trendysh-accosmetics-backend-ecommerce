package com.nbh.backend.payment;

import com.jayway.jsonpath.JsonPath;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.customer.repository.CustomerAccountRepository;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The M-PESA code is what finishes placing an order, but the order exists, and staff can see it, from the moment it is
 * placed: a customer who goes off to pay and never comes back must still show up so someone can chase them. These tests
 * follow one unpaid order through "no code yet", "code waiting for a check" and "code rejected" and check that the
 * customer's pay page, the staff orders list, the staff awaiting-payment list and the staff order panel all agree.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentCodeVisibilityIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private CustomerAccountRepository accountRepository;
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private PaymentReferenceGenerator referenceGenerator;

    private String staffToken;
    private String orderNo;
    private String reference;
    private CustomerOrder order;

    @BeforeEach
    void setUp() {
        staffToken = jwtService.generateAccessToken(
                staffUsers.findByEmailIgnoreCaseAndDeletedFalse("admin@nairobibeautyhub.co.ke").orElseThrow());
        orderNo = "CODEVIS-" + UUID.randomUUID().toString().substring(0, 8);
        reference = referenceGenerator.generate();
        order = unpaidOrder(orderNo, reference, FulfilmentType.PICKUP, "Simara Mall — Shop GS 07", OrderStatus.PENDING_PAYMENT);
    }

    private CustomerOrder unpaidOrder(String no, String ref, FulfilmentType type, String address, OrderStatus status) {
        CustomerOrder o = new CustomerOrder();
        o.setOrderNo(no);
        o.setCustomerName("Njeri Test");
        o.setGuestEmail("payer-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com");
        o.setGuestPhone("254720000111");
        o.setFulfilmentType(type);
        o.setDeliveryAddress(address);
        o.setStatus(status);
        o.setSubtotal(new BigDecimal("500.00"));
        o.setVatTotal(BigDecimal.ZERO);
        o.setGrandTotal(new BigDecimal("500.00"));
        o = orderRepository.save(o);

        PaymentRecord record = new PaymentRecord();
        record.setOrder(o);
        record.setProvider("BANK");
        record.setMethod(PaymentRecord.PaymentMethod.BANK);
        record.setAmount(new BigDecimal("500.00"));
        record.setStatus(PaymentRecord.PaymentStatus.INITIATED);
        record.setPaymentReference(ref);
        record.setExpiresAt(status == OrderStatus.EXPIRED ? Instant.now().minusSeconds(60) : Instant.now().plusSeconds(2700));
        paymentRecordRepository.save(record);
        return o;
    }

    private void claim(String no, String ref, String code) throws Exception {
        mvc.perform(post("/api/v1/payments/pay/" + no + "/claim").param("ref", ref)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionCode\":\"" + code + "\",\"amount\":500}"))
                .andExpect(status().isOk());
    }

    private String staffGet(String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** The first value a JSON path finds, as text; null when nothing matches or the value is JSON null. */
    private static String firstOf(String json, String path) {
        List<Object> values = JsonPath.read(json, path);
        return values.isEmpty() || values.get(0) == null ? null : String.valueOf(values.get(0));
    }

    private String listRow(String field) throws Exception {
        return firstOf(staffGet("/api/v1/orders?q=" + orderNo), "$.content[?(@.orderNo=='" + orderNo + "')]." + field);
    }

    private String awaitingRow(String field) throws Exception {
        return firstOf(staffGet("/api/v1/admin/payments/awaiting"), "$[?(@.orderNo=='" + orderNo + "')]." + field);
    }

    private String customerView(String field) throws Exception {
        String json = mvc.perform(get("/api/v1/payments/pay/" + orderNo).param("ref", reference))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Object value = JsonPath.read(json, "$." + field);
        return value == null ? null : String.valueOf(value);
    }

    @Test
    void aCodeForAnOrderStaffCancelledIsRefusedWithAMessageThatSaysToWhatsAppForARefund() throws Exception {
        String cancelledNo = "CODEVIS-" + UUID.randomUUID().toString().substring(0, 8);
        String cancelledRef = referenceGenerator.generate();
        unpaidOrder(cancelledNo, cancelledRef, FulfilmentType.PICKUP, "Simara Mall — Shop GS 07", OrderStatus.CANCELLED);

        String body = mvc.perform(post("/api/v1/payments/pay/" + cancelledNo + "/claim").param("ref", cancelledRef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionCode\":\"QJK7ABC123\",\"amount\":500}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("cancelled by the shop"), body);
        assertTrue(body.contains("refund you"), body);
        // An expired order is different: it still takes a late code, because the customer may have paid after the window.
        String expiredNo = "CODEVIS-" + UUID.randomUUID().toString().substring(0, 8);
        String expiredRef = referenceGenerator.generate();
        unpaidOrder(expiredNo, expiredRef, FulfilmentType.PICKUP, "Simara Mall — Shop GS 07", OrderStatus.EXPIRED);
        claim(expiredNo, expiredRef, "QJK7ABC124");
    }

    @Test
    void anOrderWithNoCodeIsOnTheStaffListsFlaggedAsNoCodeYet() throws Exception {
        assertEquals("NONE", listRow("paymentCode"));
        assertEquals("NONE", awaitingRow("codeStatus"));
        assertEquals("false", awaitingRow("underReview"));
        assertEquals("NONE", customerView("codeStatus"));
        assertEquals("false", customerView("underReview"));
        assertNull(customerView("submittedCode"));
    }

    @Test
    void aCodeMovesTheOrderToWaitingForACheckEverywhereAtOnce() throws Exception {
        claim(orderNo, reference, "qjk7abc123");

        assertEquals("PENDING", listRow("paymentCode"));
        assertEquals("PENDING", awaitingRow("codeStatus"));
        assertEquals("true", awaitingRow("underReview"));
        // Stored and shown upper case, the way M-PESA writes it, so staff compare like with like on the statement.
        assertEquals("QJK7ABC123", awaitingRow("submittedCode"));
        assertEquals("PENDING", customerView("codeStatus"));
        assertEquals("QJK7ABC123", customerView("submittedCode"));
    }

    @Test
    void aRejectedCodeSendsTheOrderBackToNeedingOne() throws Exception {
        claim(orderNo, reference, "QJK7ABC123");
        String claimId = firstOf(staffGet("/api/v1/admin/payments/claims"), "$[?(@.orderNo=='" + orderNo + "')].id");

        mvc.perform(post("/api/v1/admin/payments/claims/" + claimId + "/reject")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Not on our statement\"}"))
                .andExpect(status().isOk());

        assertEquals("REJECTED", listRow("paymentCode"));
        assertEquals("REJECTED", awaitingRow("codeStatus"));
        assertEquals("REJECTED", customerView("codeStatus"));
        // The rejected code is not shown back as if it had been accepted.
        assertNull(customerView("submittedCode"));

        claim(orderNo, reference, "QJK7ABC999");
        assertEquals("PENDING", customerView("codeStatus"));
    }

    @Test
    void aCodeUnderSixCharactersIsRefusedAndNothingIsRecorded() throws Exception {
        for (String tooShort : List.of("PAID", "ABC12", "   AB1  ", "")) {
            mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"transactionCode\":\"" + tooShort + "\",\"amount\":500}"))
                    .andExpect(status().isBadRequest());
        }
        assertEquals("NONE", customerView("codeStatus"));
    }

    @Test
    void anExpiredOrderStillTakesACodeSoALateCustomerIsNotStranded() throws Exception {
        String lateNo = "CODELATE-" + UUID.randomUUID().toString().substring(0, 8);
        String lateRef = referenceGenerator.generate();
        unpaidOrder(lateNo, lateRef, FulfilmentType.PICKUP, null, OrderStatus.EXPIRED);

        claim(lateNo, lateRef, "QLATE12345");

        assertEquals("PENDING", firstOf(staffGet("/api/v1/admin/payments/awaiting"),
                "$[?(@.orderNo=='" + lateNo + "')].codeStatus"));
    }

    @Test
    void theStaffListNamesTheShopAPickupWaitsAtAndCanBeNarrowedToIt() throws Exception {
        String otherNo = "CODESHOP-" + UUID.randomUUID().toString().substring(0, 8);
        unpaidOrder(otherNo, referenceGenerator.generate(), FulfilmentType.PICKUP, "Junction — Stalls D5",
                OrderStatus.PENDING_PAYMENT);
        String deliveryNo = "CODEDLV-" + UUID.randomUUID().toString().substring(0, 8);
        unpaidOrder(deliveryNo, referenceGenerator.generate(), FulfilmentType.DELIVERY_COURIER,
                "Simara Mall parking, Nairobi", OrderStatus.PENDING_PAYMENT);

        assertEquals("Simara Mall — Shop GS 07", listRow("pickupPlace"));

        // A request parameter, not a hand-encoded URL: MockMvc encodes the "%" of a "%20" it is given a second time.
        String simaraJson = mvc.perform(get("/api/v1/orders").param("pickupPlace", "Simara Mall").param("size", "100")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> numbers = JsonPath.read(simaraJson, "$.content[*].orderNo");
        assertTrue(numbers.contains(orderNo), "the Simara pickup is listed");
        assertFalse(numbers.contains(otherNo), "a Junction pickup is not");
        // A delivery whose address merely mentions the place is not a pickup there, so it never matches.
        assertFalse(numbers.contains(deliveryNo), "a delivery is never a pickup");
        // And a delivery shows no pickup shop in the list at all.
        assertNull(firstOf(staffGet("/api/v1/orders?q=" + deliveryNo),
                "$.content[?(@.orderNo=='" + deliveryNo + "')].pickupPlace"));
    }

    @Test
    void theOrderPanelShowsTheAccountItBelongsToOrSaysGuestAndListsEveryCodeNewestFirst() throws Exception {
        claim(orderNo, reference, "QFIRST1234");
        Thread.sleep(20);
        claim(orderNo, reference, "QSECOND123");

        String guest = staffGet("/api/v1/orders/" + order.getId());
        assertNull(JsonPath.read(guest, "$.account"));
        List<String> codes = JsonPath.read(guest, "$.claims[*].transactionCode");
        assertEquals(List.of("QSECOND123", "QFIRST1234"), codes);

        CustomerAccount account = new CustomerAccount();
        account.setEmail("member-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        account.setPassword("not-used");
        account.setFullName("Wanjiku Member");
        account.setEmailVerified(true);
        account = accountRepository.save(account);
        CustomerOrder reloaded = orderRepository.findById(order.getId()).orElseThrow();
        reloaded.setCustomerAccount(account);
        orderRepository.save(reloaded);

        String member = staffGet("/api/v1/orders/" + order.getId());
        assertEquals(account.getEmail(), JsonPath.read(member, "$.account.email"));
        assertEquals("Wanjiku Member", JsonPath.read(member, "$.account.fullName"));
        assertEquals(Boolean.TRUE, JsonPath.read(member, "$.account.emailVerified"));
    }

    @Test
    void theStaffOrdersListDoesNotCarryThePayPageReference() throws Exception {
        // The reference is the bearer secret for the pay page, so it only ever reaches the order's own owner.
        assertFalse(staffGet("/api/v1/orders?q=" + orderNo).contains(reference));
    }
}
