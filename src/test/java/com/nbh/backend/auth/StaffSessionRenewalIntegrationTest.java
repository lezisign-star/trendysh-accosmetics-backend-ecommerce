package com.nbh.backend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.customer.repository.CustomerAccountRepository;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff stay signed in while they keep working (2026-10-08). A staff token is the only credential and lasts 8 hours, so
 * someone who signed in at eight in the morning was sent back to the form mid-afternoon. {@code POST /auth/renew} swaps a
 * still-valid token for a fresh one, with limits so it is not an endless extension: the sign-in it descends from may be at
 * most a week old (the fresh token carries the ORIGINAL sign-in time), the password must not have changed since, and the
 * account must still be switched on. {@code GET /auth/me} used to mint a fresh token on every call, which would have made
 * all of that pointless; it now answers with the token it was given.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffSessionRenewalIntegrationTest {

    private static final String PASSWORD = "a-long-enough-staff-password";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private CustomerAccountRepository customers;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Value("${app.jwt.secret}") private String secret;

    private StaffUser newStaff(String role) {
        StaffUser user = new StaffUser();
        user.setEmail("renew-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setFirstName("Renew");
        user.setLastName("Test");
        user.setStaffRole(staffRoles.findByName(role).orElseThrow());
        return staffUsers.save(user);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions signInWith(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private String signIn(StaffUser staff) throws Exception {
        return body(signInWith(staff.getEmail(), PASSWORD).andExpect(status().isOk())).get("accessToken").asText();
    }

    private ResultActions renew(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/renew").header("Authorization", "Bearer " + token));
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token));
    }

    @Test
    void aRenewedTokenIsForTheSamePersonAndCarriesTheOriginalSignInTime() throws Exception {
        StaffUser staff = newStaff("CASHIER");
        String first = signIn(staff);
        Instant signedIn = jwtService.extractSignedInAt(first);
        Thread.sleep(1100); // tokens are stamped to the second; wait so the renewed one is visibly newer

        JsonNode renewed = body(renew(first).andExpect(status().isOk()));
        String second = renewed.get("accessToken").asText();

        assertThat(renewed.get("email").asText()).isEqualTo(staff.getEmail());
        assertThat(second).isNotEqualTo(first);
        assertThat(jwtService.extractSignedInAt(second)).as("renewing never moves the limit").isEqualTo(signedIn);
        assertThat(jwtService.extractClaim(second, Claims::getIssuedAt))
                .isAfter(jwtService.extractClaim(first, Claims::getIssuedAt));
        me(second).andExpect(status().isOk());
    }

    @Test
    void aRenewalBringsThePersonsCurrentRoleAndPermissions() throws Exception {
        StaffUser staff = newStaff("CASHIER");
        String token = signIn(staff);
        staff.setStaffRole(staffRoles.findByName("MANAGER").orElseThrow());
        staffUsers.save(staff);

        JsonNode renewed = body(renew(token).andExpect(status().isOk()));

        assertThat(renewed.get("staffRole").asText()).isEqualTo("MANAGER");
        assertThat(json.convertValue(renewed.get("permissions"), String[].class)).contains("PRODUCT_MANAGE");
    }

    @Test
    void aSessionPastTheLimitCannotBeRenewedButOneInsideItCan() throws Exception {
        StaffUser staff = newStaff("CASHIER");
        String eightDaysOld = jwtService.generateAccessToken(staff, Instant.now().minus(Duration.ofDays(8)));
        String sixDaysOld = jwtService.generateAccessToken(staff, Instant.now().minus(Duration.ofDays(6)));

        renew(eightDaysOld).andExpect(status().isUnauthorized());
        renew(sixDaysOld).andExpect(status().isOk());
        // Refusing to renew does not end the token it was given: that keeps working until it runs out.
        me(eightDaysOld).andExpect(status().isOk());
    }

    @Test
    void aTokenIssuedBeforeThisFeatureStillWorksAndRenewsIntoOneThatRemembersItsSignIn() throws Exception {
        StaffUser staff = newStaff("CASHIER");
        Instant issued = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        claims.put("kind", "STAFF");
        String legacy = Jwts.builder().claims(claims).subject(staff.getEmail())
                .issuedAt(Date.from(issued)).expiration(Date.from(Instant.now().plus(Duration.ofHours(5))))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();

        me(legacy).andExpect(status().isOk());
        JsonNode renewed = body(renew(legacy).andExpect(status().isOk()));

        assertThat(jwtService.extractSignedInAt(renewed.get("accessToken").asText()))
                .as("a token with no recorded sign-in counts from when it was issued")
                .isEqualTo(issued);
    }

    @Test
    void changingThePasswordEndsTheAbilityToRenewEarlierTokens() throws Exception {
        StaffUser staff = newStaff("CASHIER");
        String token = signIn(staff);
        staff.setPassword(passwordEncoder.encode("a-completely-new-password"));
        staffUsers.save(staff);

        renew(token).andExpect(status().isUnauthorized());
        me(token).andExpect(status().isOk()); // not revoked, just no longer extendable

        String fresh = body(signInWith(staff.getEmail(), "a-completely-new-password").andExpect(status().isOk()))
                .get("accessToken").asText();
        renew(fresh).andExpect(status().isOk());
    }

    @Test
    void aSwitchedOffAccountCannotRenew() throws Exception {
        StaffUser staff = newStaff("CASHIER");
        String token = signIn(staff);
        staff.setEnabled(false);
        staffUsers.save(staff);

        assertThat(renew(token).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    @Test
    void aShoppersTokenCannotRenewAStaffSession() throws Exception {
        CustomerAccount shopper = new CustomerAccount();
        shopper.setEmail("shopper-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        shopper.setPassword(passwordEncoder.encode(PASSWORD));
        shopper.setFullName("Wanjiru Shopper");
        customers.save(shopper);
        String shopperToken = body(mvc.perform(post("/api/v1/customer/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + shopper.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())).get("accessToken").asText();

        renew(shopperToken).andExpect(status().isForbidden());
    }

    @Test
    void renewingNeedsAToken() throws Exception {
        assertThat(mvc.perform(post("/api/v1/auth/renew")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    @Test
    void askingWhoIAmDoesNotHandOutAFreshToken() throws Exception {
        StaffUser staff = newStaff("CASHIER");
        String token = signIn(staff);

        JsonNode who = body(me(token).andExpect(status().isOk()));

        assertThat(who.get("accessToken").asText()).as("the token it was called with, not a new one").isEqualTo(token);
        assertThat(who.get("email").asText()).isEqualTo(staff.getEmail());
    }
}
