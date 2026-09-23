package com.nbh.backend.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end through the real security filter chain and database: shopper
 * accounts work, a shopper's token can never reach staff endpoints (and the
 * reverse), and — the privacy-critical part — earlier guest orders only join an
 * account after the email is verified with the emailed link, and one shopper
 * can never read another's order.
 *
 * Not @Transactional on purpose: the emails go out AFTER COMMIT, and the flow
 * spans several HTTP requests. Every run uses unique emails.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerAccountsIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern LINK_TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CustomerOrderRepository orders;
    @Autowired private BranchRepository branches;
    @MockitoBean private EmailSender emailSender;

    private static String uniqueEmail() {
        return "shopper-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private JsonNode post(String path, String body, String bearer) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        MvcResult result = mvc.perform(request).andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? json.createObjectNode().put("_status", result.getResponse().getStatus())
                : ((com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(content))
                        .put("_status", result.getResponse().getStatus());
    }

    private String register(String email) throws Exception {
        JsonNode res = post("/api/v1/customer/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Njeri Test\",\"phone\":\"0720 000 111\"}", null);
        assertThat(res.get("_status").asInt()).isEqualTo(200);
        return res.get("accessToken").asText();
    }

    private String staffToken() {
        return jwtService.generateAccessToken(
                staffUsers.findByEmailIgnoreCaseAndDeletedFalse("admin@nairobibeautyhub.co.ke").orElseThrow());
    }

    private String lastLinkToken(String toEmail) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(eq(toEmail), anyString(), body.capture());
        Matcher m = LINK_TOKEN.matcher(body.getValue());
        assertThat(m.find()).as("email contains a link token").isTrue();
        return m.group(1);
    }

    private String placeGuestOrder(String email) {
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("GUEST-" + UUID.randomUUID().toString().substring(0, 8));
        order.setGuestEmail(email);
        order.setGuestPhone("254720000111");
        order.setFulfilmentBranch(branches.findByCode("BR-A").orElseThrow());
        order.setFulfilmentType(FulfilmentType.BRANCH_PICKUP);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setSubtotal(new BigDecimal("500.00"));
        order.setVatTotal(BigDecimal.ZERO);
        order.setGrandTotal(new BigDecimal("500.00"));
        return orders.save(order).getOrderNo();
    }

    @Test
    void aShoppersTokenCannotReachStaffEndpointsAndStaffTokensCannotReachShopperOnes() throws Exception {
        String customerToken = register(uniqueEmail());
        String staffToken = staffToken();

        mvc.perform(get("/api/v1/customer/auth/me").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());

        // staff-only endpoints: permission-gated ones and one that is merely "authenticated"
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        // and the reverse: a staff token is not a shopper
        mvc.perform(get("/api/v1/customer/orders").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/customer/orders")).andExpect(status().is4xxClientError());
    }

    @Test
    void aShopperCannotRegisterOnAStaffEmailAndStaffLoginIsUnaffected() throws Exception {
        String staffEmail = "admin@nairobibeautyhub.co.ke";
        // Blocked outright — see CustomerAuthService.register()'s javadoc: a shopper account on a
        // staff email could never be signed into anyway (staff are checked first on the shared
        // sign-in page), and allowing the attempt at all would let someone probe which emails are staff.
        JsonNode created = post("/api/v1/customer/auth/register",
                "{\"email\":\"" + staffEmail + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Shopper\"}", null);
        assertThat(created.get("_status").asInt()).isEqualTo(409);

        // Staff sign-in and staff-only access are unaffected.
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + staffToken()))
                .andExpect(status().isOk());
    }

    @Test
    void earlierGuestOrdersOnlyJoinAnAccountAfterTheEmailIsVerified() throws Exception {
        String email = uniqueEmail();
        String guestOrderNo = placeGuestOrder(email);

        String token = register(email);

        // Registered but unverified: the guest order must NOT be visible yet.
        mvc.perform(get("/api/v1/customer/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));

        // Following the emailed link verifies the address and adopts the order.
        JsonNode verified = post("/api/v1/customer/auth/verify-email",
                "{\"token\":\"" + lastLinkToken(email) + "\"}", null);
        assertThat(verified.get("emailVerified").asBoolean()).isTrue();
        assertThat(verified.get("ordersLinked").asInt()).isEqualTo(1);

        mvc.perform(get("/api/v1/customer/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].orderNo").value(guestOrderNo));
        mvc.perform(get("/api/v1/customer/orders/" + guestOrderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Following the same link again is harmless.
        assertThat(post("/api/v1/customer/auth/verify-email",
                "{\"token\":\"" + lastLinkToken(email) + "\"}", null).get("_status").asInt()).isEqualTo(200);
    }

    @Test
    void oneShopperCannotReadAnotherShoppersOrder() throws Exception {
        String ownerEmail = uniqueEmail();
        String orderNo = placeGuestOrder(ownerEmail);
        String ownerToken = register(ownerEmail);
        post("/api/v1/customer/auth/verify-email", "{\"token\":\"" + lastLinkToken(ownerEmail) + "\"}", null);
        String strangerToken = register(uniqueEmail());

        mvc.perform(get("/api/v1/customer/orders/" + orderNo).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        // Exactly the same answer as for an order that does not exist.
        mvc.perform(get("/api/v1/customer/orders/" + orderNo).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongPasswordsAreRefusedGenericallyAndRepeatedFailuresAreThrottled() throws Exception {
        String email = uniqueEmail();
        register(email);

        JsonNode unknown = post("/api/v1/customer/auth/login",
                "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"whatever-long\"}", null);
        JsonNode wrong = post("/api/v1/customer/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"wrong-password-1\"}", null);
        assertThat(unknown.get("_status").asInt()).isEqualTo(401);
        assertThat(wrong.get("_status").asInt()).isEqualTo(401);
        assertThat(wrong.get("error").asText()).isEqualTo(unknown.get("error").asText());

        for (int i = 0; i < 5; i++) {
            post("/api/v1/customer/auth/login", "{\"email\":\"" + email + "\",\"password\":\"wrong-password-2\"}", null);
        }
        // Locked out — even the right password is refused for now.
        assertThat(post("/api/v1/customer/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null).get("_status").asInt())
                .isEqualTo(429);
    }

    @Test
    void duplicateRegistrationIsRejectedRegardlessOfCase() throws Exception {
        String email = uniqueEmail();
        register(email);

        JsonNode again = post("/api/v1/customer/auth/register",
                "{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Dup\"}", null);
        assertThat(again.get("_status").asInt()).isEqualTo(409);
    }
}
