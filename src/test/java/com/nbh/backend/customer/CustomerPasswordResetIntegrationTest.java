package com.nbh.backend.customer;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The shopper side of the shared forgot/reset-password flow (staff coverage lives in
 * {@code StaffPasswordResetIntegrationTest}). A 6-digit code is emailed, single use, locks out
 * after 5 wrong guesses, and — like the old emailed link — proves control of the mailbox, so a
 * successful reset also verifies the email and attaches any earlier guest orders.
 *
 * Not @Transactional: emails go out after commit. Every run uses unique emails.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerPasswordResetIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    /** The code sits alone on its own line in the text body, between the intro line and the expiry note. */
    private static final Pattern OTP_CODE = Pattern.compile("\\n(\\d{6})\\n");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private CustomerOrderRepository orders;
    @MockitoBean private EmailSender emailSender;

    private static String uniqueEmail() {
        return "shopper-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private JsonNode post(String path, String body) throws Exception {
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? json.createObjectNode().put("_status", result.getResponse().getStatus())
                : ((com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(content))
                        .put("_status", result.getResponse().getStatus());
    }

    private String register(String email) throws Exception {
        JsonNode res = post("/api/v1/customer/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Njeri Test\",\"phone\":\"0720 000 111\"}");
        assertThat(res.get("_status").asInt()).isEqualTo(200);
        return res.get("accessToken").asText();
    }

    /** Requests a code and returns the most recently emailed one (a test may request more than once). */
    private String requestOtpCode(String email) throws Exception {
        post("/api/v1/customer/auth/forgot-password", "{\"email\":\"" + email + "\"}");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        // The OTP flow always has an HTML alternative, so the 4-arg overload is the one actually called
        // (distinct from the 3-arg verify-email sent at registration, which this correctly ignores).
        verify(emailSender, atLeastOnce()).send(eq(email), anyString(), body.capture(), anyString());
        List<String> bodies = body.getAllValues();
        Matcher m = OTP_CODE.matcher(bodies.get(bodies.size() - 1));
        assertThat(m.find()).as("email contains a 6-digit code").isTrue();
        return m.group(1);
    }

    private JsonNode resetPassword(String email, String code, String newPassword) throws Exception {
        return post("/api/v1/customer/auth/reset-password",
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + newPassword + "\"}");
    }

    private String placeGuestOrder(String email) {
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("GUEST-" + UUID.randomUUID().toString().substring(0, 8));
        order.setGuestEmail(email);
        order.setGuestPhone("254720000111");
        order.setFulfilmentType(FulfilmentType.PICKUP);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setSubtotal(new BigDecimal("500.00"));
        order.setVatTotal(BigDecimal.ZERO);
        order.setGrandTotal(new BigDecimal("500.00"));
        return orders.save(order).getOrderNo();
    }

    @Test
    void aShopperCanResetThroughTheEmailedCodeAndSignInWithTheNewPassword() throws Exception {
        String email = uniqueEmail();
        register(email);
        String code = requestOtpCode(email);

        assertThat(resetPassword(email, code, "a-brand-new-password").get("_status").asInt()).isEqualTo(200);

        JsonNode login = post("/api/v1/customer/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"a-brand-new-password\"}");
        assertThat(login.get("_status").asInt()).isEqualTo(200);
        // The old password no longer works.
        assertThat(post("/api/v1/customer/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").get("_status").asInt())
                .isEqualTo(401);
    }

    @Test
    void aSuccessfulResetVerifiesTheEmailAndLinksEarlierGuestOrders() throws Exception {
        String email = uniqueEmail();
        String guestOrderNo = placeGuestOrder(email);
        String token = register(email);
        String code = requestOtpCode(email);

        JsonNode result = resetPassword(email, code, "a-brand-new-password");
        assertThat(result.get("_status").asInt()).isEqualTo(200);

        JsonNode login = post("/api/v1/customer/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"a-brand-new-password\"}");
        assertThat(login.get("profile").get("emailVerified").asBoolean()).isTrue();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/customer/orders").header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].orderNo").value(guestOrderNo));
    }

    @Test
    void theCodeWorksOnlyOnce() throws Exception {
        String email = uniqueEmail();
        register(email);
        String code = requestOtpCode(email);
        assertThat(resetPassword(email, code, "first-new-password").get("_status").asInt()).isEqualTo(200);
        assertThat(resetPassword(email, code, "second-new-password").get("_status").asInt()).isEqualTo(422);
    }

    @Test
    void anUnknownEmailLooksIdenticalAndSendsNothing() throws Exception {
        String stranger = uniqueEmail();
        assertThat(post("/api/v1/customer/auth/forgot-password", "{\"email\":\"" + stranger + "\"}")
                .get("_status").asInt()).isEqualTo(200);
        verifyNoInteractions(emailSender);
    }

    @Test
    void wrongCodeGuessesAreCountedAndLockOutAfterFive() throws Exception {
        String email = uniqueEmail();
        register(email);
        String code = requestOtpCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 4; i++) {
            JsonNode res = resetPassword(email, wrong, "SomePassword123");
            assertThat(res.get("_status").asInt()).isEqualTo(422);
            int left = 4 - i;
            assertThat(res.get("error").asText())
                    .isEqualTo("Incorrect code — " + left + " attempt" + (left == 1 ? "" : "s") + " left");
        }
        JsonNode fifth = resetPassword(email, wrong, "SomePassword123");
        assertThat(fifth.get("error").asText()).isEqualTo("Too many incorrect attempts — please request a new code");

        // Even the CORRECT code is now rejected.
        assertThat(resetPassword(email, code, "SomePassword123").get("_status").asInt()).isEqualTo(422);
    }
}
