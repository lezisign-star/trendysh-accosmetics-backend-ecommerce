package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.service.OrderPaymentReminderSweeper;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Before 2026-10-06 the shop sent no order emails at all (the pay page even promised one). Now a customer gets: one email
 * when the order is placed, with the way to pay and the link to type the M-PESA code; one reminder fifteen minutes later
 * if no code has arrived; and a receipt when the payment is confirmed. The mail provider is replaced by a mock, and the
 * test profile sends on the calling thread, so a send can be checked as soon as the request that caused it returns.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderEmailsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderPaymentReminderSweeper sweeper;
    @MockitoBean private EmailSender emailSender;

    private String staffToken;
    private String variantId;

    @BeforeEach
    void setUp() throws Exception {
        reset(emailSender);
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
        variantId = newVariantWithStock();
    }

    private JsonNode okStaff(MockHttpServletRequestBuilder request) throws Exception {
        request.header("Authorization", "Bearer " + staffToken);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String newVariantWithStock() throws Exception {
        String body = "{\"name\":\"Email Test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variant = product.get("variants").get(0).get("id").asText();
        okStaff(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/products/" + productId + "/variants/" + variant + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":500,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        return variant;
    }

    /** What the shopper got back for an order: its number, the reference that opens the pay page, and the email used. */
    private record Placed(String orderNo, String reference, String email) {}

    private Placed placeOrder(String address) throws Exception {
        String session = UUID.randomUUID().toString();
        MvcResult added = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":\"" + variantId + "\",\"qty\":1}")).andReturn();
        assertThat(added.getResponse().getStatus()).isEqualTo(200);
        String email = TestContacts.email();
        String body = """
                {"customerName":"Njeri Christine","guestEmail":"%s","guestPhone":"%s","fulfilmentType":"PICKUP",
                 "deliveryAddress":"%s"}
                """.formatted(email, TestContacts.phone(), address);
        MvcResult placed = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        String response = placed.getResponse().getContentAsString();
        return new Placed(JsonPath.read(response, "$.order.orderNo"), JsonPath.read(response, "$.paymentInstructions.reference"), email);
    }

    private void backdate(String orderNo, int minutes) {
        jdbc.update("UPDATE customer_order SET created_at = now() - (? * interval '1 minute') WHERE order_no = ?", minutes, orderNo);
    }

    /** Every email sent to one address: [subject, text, html] per send. */
    private List<String[]> sentTo(String email) {
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailSender, org.mockito.Mockito.atLeast(0)).send(eq(email), subject.capture(), text.capture(), html.capture());
        List<String[]> sends = new java.util.ArrayList<>();
        for (int i = 0; i < subject.getAllValues().size(); i++) {
            sends.add(new String[] {subject.getAllValues().get(i), text.getAllValues().get(i), html.getAllValues().get(i)});
        }
        return sends;
    }

    private void claimCode(Placed order, String code) throws Exception {
        MvcResult claim = mvc.perform(post("/api/v1/payments/pay/" + order.orderNo() + "/claim").param("ref", order.reference())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionCode\":\"" + code + "\",\"amount\":1000}")).andReturn();
        assertThat(claim.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void placingAnOrderEmailsTheWayToPayAndTheLinkBackToTheCodeBox() throws Exception {
        Placed order = placeOrder("Simara Mall — Shop GS 07");

        List<String[]> sends = sentTo(order.email());
        assertThat(sends).as("exactly one email at checkout").hasSize(1);
        String subject = sends.get(0)[0];
        String text = sends.get(0)[1];
        String html = sends.get(0)[2];
        String link = "/pay/" + order.orderNo() + "?ref=" + order.reference();

        assertThat(subject).contains(order.orderNo()).containsIgnoringCase("pay with M-PESA");
        assertThat(text).contains("Hi Njeri,").contains(link).contains("KSh 1,000").contains("M-PESA code");
        assertThat(html).contains("href=\"http://localhost:5250" + link + "\"").contains("Pay and enter my M-PESA code")
                .contains("KSh 1,000");
    }

    @Test
    void aQuietCustomerGetsOneReminderFifteenMinutesLaterAndNeverTwo() throws Exception {
        Placed order = placeOrder("Junction — Stalls D5");
        reset(emailSender);

        sweeper.remindQuietCustomers();
        assertThat(sentTo(order.email())).as("too soon: nothing yet").isEmpty();

        backdate(order.orderNo(), 20);
        sweeper.remindQuietCustomers();
        List<String[]> sends = sentTo(order.email());
        assertThat(sends).as("one reminder").hasSize(1);
        assertThat(sends.get(0)[0]).startsWith("Reminder:").contains(order.orderNo());
        assertThat(sends.get(0)[1]).contains("/pay/" + order.orderNo() + "?ref=" + order.reference());

        sweeper.remindQuietCustomers();
        assertThat(sentTo(order.email())).as("never a second one").hasSize(1);
    }

    @Test
    void noReminderOnceACodeHasArrivedTheOrderIsClosedOrTheStockHoldHasEnded() throws Exception {
        Placed withCode = placeOrder("Junction — Stalls D5");
        claimCode(withCode, "QREMIND123");
        Placed paid = placeOrder("Junction — Stalls D5");
        Placed holdEnded = placeOrder("Junction — Stalls D5");
        for (Placed p : List.of(withCode, paid, holdEnded)) backdate(p.orderNo(), 20);
        jdbc.update("UPDATE customer_order SET status = 'PAID' WHERE order_no = ?", paid.orderNo());
        jdbc.update("UPDATE payment_record SET expires_at = now() - interval '1 minute' WHERE order_id = "
                + "(SELECT id FROM customer_order WHERE order_no = ?)", holdEnded.orderNo());
        reset(emailSender);

        sweeper.remindQuietCustomers();

        for (Placed p : List.of(withCode, paid, holdEnded)) {
            assertThat(sentTo(p.email())).as("no reminder for " + p.orderNo()).isEmpty();
        }
    }

    @Test
    void confirmingThePaymentSendsAReceiptNamingWhereToCollectIt() throws Exception {
        Placed order = placeOrder("Junction — Stalls <D5>");
        claimCode(order, "QRECEIPT12");
        reset(emailSender);

        String claims = mvc.perform(get("/api/v1/admin/payments/claims").header("Authorization", "Bearer " + staffToken))
                .andReturn().getResponse().getContentAsString();
        String claimId = JsonPath.<List<String>>read(claims, "$[?(@.orderNo=='" + order.orderNo() + "')].id").get(0);
        MvcResult approved = mvc.perform(post("/api/v1/admin/payments/claims/" + claimId + "/approve")
                .header("Authorization", "Bearer " + staffToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"seen on the statement\"}")).andReturn();
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);

        List<String[]> sends = sentTo(order.email());
        assertThat(sends).as("one receipt").hasSize(1);
        assertThat(sends.get(0)[0]).isEqualTo("Payment received for order " + order.orderNo());
        assertThat(sends.get(0)[1]).contains("/track?order=" + order.orderNo()).contains("Junction — Stalls <D5>");
        // The address came from the customer, so in the HTML it is text, never markup.
        assertThat(sends.get(0)[2]).contains("Stalls &lt;D5&gt;").doesNotContain("<D5>");
        verify(emailSender, never()).send(eq(order.email()), org.mockito.ArgumentMatchers.startsWith("Reminder"), anyString(), anyString());
        verify(emailSender, times(1)).send(eq(order.email()), anyString(), anyString(), anyString());
    }
}
