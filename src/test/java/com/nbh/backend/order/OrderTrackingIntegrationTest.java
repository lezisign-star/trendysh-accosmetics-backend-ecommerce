package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Guest order lookup (2026-09-28): an order number alone reveals only a status; seeing the orders
 * under an email needs a code emailed to that mailbox. Not @Transactional — emails go out after
 * commit — so every test uses its own unique email and order numbers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderTrackingIntegrationTest {

    /** The code sits alone on its own line in the text body. */
    private static final Pattern OTP_CODE = Pattern.compile("\\n(\\d{6})\\n");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private CustomerOrderRepository orders;
    @MockitoBean private EmailSender emailSender;

    private static String uniqueEmail() {
        return "guest-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private CustomerOrder newOrder(String email, OrderStatus status) {
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("TRK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setGuestEmail(email);
        order.setGuestPhone(TestContacts.phone());
        order.setCustomerName("Njeri Test");
        order.setFulfilmentType(FulfilmentType.PICKUP);
        order.setStatus(status);
        order.setSubtotal(new BigDecimal("1200.00"));
        order.setVatTotal(BigDecimal.ZERO);
        order.setGrandTotal(new BigDecimal("1200.00"));
        return orders.save(order);
    }

    private MvcResult getJson(String path, String token) throws Exception {
        var request = get(path);
        if (token != null) request.header("X-Track-Token", token);
        return mvc.perform(request).andReturn();
    }

    private MvcResult postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String requestCodeAndReadIt(String email) throws Exception {
        MvcResult result = postJson("/api/v1/orders/track/email/code", "{\"email\":\"" + email + "\"}");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(eq(email), anyString(), body.capture(), anyString());
        List<String> bodies = body.getAllValues();
        Matcher m = OTP_CODE.matcher(bodies.get(bodies.size() - 1));
        assertThat(m.find()).as("email contains a 6-digit code").isTrue();
        return m.group(1);
    }

    private String verifiedToken(String email) throws Exception {
        String code = requestCodeAndReadIt(email);
        MvcResult verified = postJson("/api/v1/orders/track/email/verify",
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}");
        assertThat(verified.getResponse().getStatus()).as(verified.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(verified.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void anOrderNumberAloneRevealsOnlyItsStatus() throws Exception {
        CustomerOrder order = newOrder(uniqueEmail(), OrderStatus.DISPATCHED);

        MvcResult result = getJson("/api/v1/orders/track/" + order.getOrderNo().toLowerCase(), null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("orderNo").asText()).isEqualTo(order.getOrderNo());
        assertThat(body.get("status").asText()).isEqualTo("DISPATCHED");
        // Nothing beyond the status: no items, totals, contact or delivery details.
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("orderNo", "status");
    }

    @Test
    void addingAnEmailToTheOrderNumberDoesNotUnlockMore() throws Exception {
        String email = uniqueEmail();
        CustomerOrder order = newOrder(email, OrderStatus.PAID);

        MvcResult result = getJson("/api/v1/orders/track/" + order.getOrderNo() + "?email=" + email, null);

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("orderNo", "status");
    }

    @Test
    void anUnknownOrderNumberIs404() throws Exception {
        assertThat(getJson("/api/v1/orders/track/NOPE-000", null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aVerifiedEmailListsItsOrdersAndOpensOneOfThem() throws Exception {
        String email = uniqueEmail();
        CustomerOrder first = newOrder(email, OrderStatus.DELIVERED);
        CustomerOrder second = newOrder(email, OrderStatus.PAID);
        newOrder(uniqueEmail(), OrderStatus.PAID); // someone else's
        String token = verifiedToken(email);

        JsonNode list = json.readTree(getJson("/api/v1/orders/track/email/orders", token).getResponse().getContentAsString());
        assertThat(list).hasSize(2);
        assertThat(List.of(list.get(0).get("orderNo").asText(), list.get(1).get("orderNo").asText()))
                .containsExactlyInAnyOrder(first.getOrderNo(), second.getOrderNo());

        JsonNode filtered = json.readTree(getJson("/api/v1/orders/track/email/orders?orderNo=" + first.getOrderNo().toLowerCase(),
                token).getResponse().getContentAsString());
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("orderNo").asText()).isEqualTo(first.getOrderNo());

        JsonNode detail = json.readTree(getJson("/api/v1/orders/track/email/orders/" + second.getOrderNo(), token)
                .getResponse().getContentAsString());
        assertThat(detail.get("orderNo").asText()).isEqualTo(second.getOrderNo());
        assertThat(detail.get("status").asText()).isEqualTo("PAID");
        assertThat(detail.has("items")).isTrue();
    }

    @Test
    void anotherEmailsOrderIsNotFoundEvenWithAValidSession() throws Exception {
        String email = uniqueEmail();
        newOrder(email, OrderStatus.PAID);
        CustomerOrder someoneElses = newOrder(uniqueEmail(), OrderStatus.PAID);
        String token = verifiedToken(email);

        assertThat(getJson("/api/v1/orders/track/email/orders/" + someoneElses.getOrderNo(), token).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void noCodeIsEmailedForAnAddressWithNoOrdersButTheAnswerIsTheSame() throws Exception {
        String email = uniqueEmail();

        MvcResult result = postJson("/api/v1/orders/track/email/code", "{\"email\":\"" + email + "\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        verify(emailSender, never()).send(eq(email), anyString(), anyString(), anyString());
    }

    @Test
    void aCodeIsSingleUseAndFiveWrongGuessesBurnIt() throws Exception {
        String email = uniqueEmail();
        newOrder(email, OrderStatus.PAID);
        String code = requestCodeAndReadIt(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            assertThat(postJson("/api/v1/orders/track/email/verify",
                    "{\"email\":\"" + email + "\",\"code\":\"" + wrong + "\"}").getResponse().getStatus()).isEqualTo(422);
        }
        // The right code no longer works: too many wrong guesses burned it.
        assertThat(postJson("/api/v1/orders/track/email/verify",
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}").getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void aCodeCannotBeUsedTwice() throws Exception {
        String email = uniqueEmail();
        newOrder(email, OrderStatus.PAID);
        String code = requestCodeAndReadIt(email);
        String body = "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}";

        assertThat(postJson("/api/v1/orders/track/email/verify", body).getResponse().getStatus()).isEqualTo(200);
        assertThat(postJson("/api/v1/orders/track/email/verify", body).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void listingOrdersWithoutAValidSessionIs401() throws Exception {
        assertThat(getJson("/api/v1/orders/track/email/orders", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(getJson("/api/v1/orders/track/email/orders", "not-a-real-token").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aVerifiedCustomerSeesTheirRidersTrackingLinkButAnOrderNumberAloneDoesNot() throws Exception {
        String email = uniqueEmail();
        CustomerOrder order = newOrder(email, OrderStatus.DISPATCHED);
        order.setTumabodaTrackingUrl("https://track.tumaboda.test/TRK9");
        order.setTumabodaTrackingCode("TRK9");
        order.setTumabodaStatus("in_transit");
        orders.save(order);
        String token = verifiedToken(email);

        JsonNode detail = json.readTree(getJson("/api/v1/orders/track/email/orders/" + order.getOrderNo(), token)
                .getResponse().getContentAsString());
        assertThat(detail.get("tumabodaTrackingUrl").asText()).isEqualTo("https://track.tumaboda.test/TRK9");
        assertThat(detail.get("tumabodaTrackingCode").asText()).isEqualTo("TRK9");
        assertThat(detail.get("tumabodaStatus").asText()).isEqualTo("in_transit");

        JsonNode byNumber = json.readTree(getJson("/api/v1/orders/track/" + order.getOrderNo(), null)
                .getResponse().getContentAsString());
        assertThat(byNumber.fieldNames()).toIterable().containsExactlyInAnyOrder("orderNo", "status");
    }

    @Test
    void askingForCodesTwiceInARowIsThrottled() throws Exception {
        String email = uniqueEmail();
        newOrder(email, OrderStatus.PAID);

        assertThat(postJson("/api/v1/orders/track/email/code", "{\"email\":\"" + email + "\"}").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(postJson("/api/v1/orders/track/email/code", "{\"email\":\"" + email + "\"}").getResponse().getStatus())
                .isEqualTo(429);
    }
}
