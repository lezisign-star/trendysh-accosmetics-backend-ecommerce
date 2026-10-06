package com.nbh.backend.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.customer.repository.CustomerRefreshTokenRepository;
import com.nbh.backend.customer.service.CustomerAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shopper session hardening, end to end: the refresh token lives only in an httpOnly cookie,
 * rotates on every use, a replayed old token kills its whole family, and logging out or changing
 * the password ends sessions. Not @Transactional — the flow spans several HTTP requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerRefreshTokenIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String COOKIE = "trendysh_rt";
    private static final String ALLOWED_ORIGIN = "http://localhost:5250";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private CustomerRefreshTokenRepository refreshTokens;
    @MockitoBean private EmailSender emailSender;

    private record Login(String accessToken, String refreshCookie, String setCookieHeader) {}

    private Login login() throws Exception {
        String email = "shopper-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        MvcResult result = mvc.perform(post("/api/v1/customer/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Wanjiru Test\"}"))
                .andExpect(status().isOk()).andReturn();
        return toLogin(result);
    }

    private Login toLogin(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        Cookie cookie = result.getResponse().getCookie(COOKIE);
        assertThat(cookie).as("refresh cookie is set").isNotNull();
        return new Login(body.get("accessToken").asText(), cookie.getValue(), result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
    }

    private MvcResult refresh(String cookieValue) throws Exception {
        return mvc.perform(post("/api/v1/customer/auth/refresh")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new Cookie(COOKIE, cookieValue))).andReturn();
    }

    @Test
    void loginSetsAnHttpOnlyCookieAndNeverPutsTheRefreshTokenInTheBody() throws Exception {
        Login login = login();

        assertThat(login.setCookieHeader()).contains("HttpOnly").contains("Secure").contains("SameSite=None")
                .contains("Path=/api/v1/customer/auth");
        MvcResult reLogin = mvc.perform(post("/api/v1/customer/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + json.readTree(
                        mvc.perform(get("/api/v1/customer/auth/me").header("Authorization", "Bearer " + login.accessToken()))
                                .andReturn().getResponse().getContentAsString()).get("email").asText()
                        + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(reLogin.getResponse().getContentAsString()).doesNotContain(login.refreshCookie());
    }

    @Test
    void refreshRotatesTheCookieAndIssuesAWorkingAccessToken() throws Exception {
        Login first = login();

        MvcResult refreshed = refresh(first.refreshCookie());
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        Login second = toLogin(refreshed);

        assertThat(second.refreshCookie()).isNotEqualTo(first.refreshCookie());
        mvc.perform(get("/api/v1/customer/auth/me").header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void replayingAnOldRefreshTokenAfterTheGraceWindowRevokesTheWholeSession() throws Exception {
        Login first = login();
        Login second = toLogin(refresh(first.refreshCookie()));

        // Pretend the first token was rotated a minute ago, i.e. this is not a two-tab race.
        var old = refreshTokens.findByTokenHash(CustomerAuthService.hash(first.refreshCookie())).orElseThrow();
        old.setRotatedAt(Instant.now().minusSeconds(60));
        refreshTokens.save(old);

        assertThat(refresh(first.refreshCookie()).getResponse().getStatus()).isEqualTo(401);
        // The thief presenting the stale token also burned the legitimate holder's newer token.
        assertThat(refresh(second.refreshCookie()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void twoTabsRefreshingAtTheSameTimeBothSucceed() throws Exception {
        Login first = login();
        assertThat(refresh(first.refreshCookie()).getResponse().getStatus()).isEqualTo(200);
        // Same cookie again, a moment later — the losing tab of a race.
        assertThat(refresh(first.refreshCookie()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        Login login = login();

        MvcResult out = mvc.perform(post("/api/v1/customer/auth/logout")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new Cookie(COOKIE, login.refreshCookie()))).andReturn();
        assertThat(out.getResponse().getStatus()).isEqualTo(200);
        assertThat(out.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        assertThat(refresh(login.refreshCookie()).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refreshWithoutACookieOrWithAGarbageCookieIsRejected() throws Exception {
        mvc.perform(post("/api/v1/customer/auth/refresh").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized());
        assertThat(refresh("not-a-real-token").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refreshFromAnUnknownOriginIsRefusedEvenWithAValidCookie() throws Exception {
        Login login = login();
        MvcResult result = mvc.perform(post("/api/v1/customer/auth/refresh")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .cookie(new Cookie(COOKIE, login.refreshCookie()))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        // ...and the refused attempt did not consume the token.
        assertThat(refresh(login.refreshCookie()).getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * Found in the 2026-10-06 live QA: a mistyped current password answered 401, which to every signed-in page means
     * "your session is gone", so the shopper was signed out for a typo. It is a 422 with the reason, and the session lives.
     */
    @Test
    void aMistypedCurrentPasswordIsRefusedWithAReasonAndDoesNotEndTheSession() throws Exception {
        Login device = login();

        MvcResult refused = mvc.perform(post("/api/v1/customer/auth/change-password")
                .header("Authorization", "Bearer " + device.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"not-my-password\",\"newPassword\":\"another-long-password\"}")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("Current password is incorrect");
        assertThat(mvc.perform(get("/api/v1/customer/auth/me")
                .header("Authorization", "Bearer " + device.accessToken())).andReturn().getResponse().getStatus())
                .as("the same session still works").isEqualTo(200);
    }

    @Test
    void changingThePasswordSignsOutOtherDevicesButKeepsThisOneSignedIn() throws Exception {
        Login device = login();
        String email = json.readTree(mvc.perform(get("/api/v1/customer/auth/me")
                .header("Authorization", "Bearer " + device.accessToken())).andReturn().getResponse().getContentAsString())
                .get("email").asText();
        MvcResult otherDevice = mvc.perform(post("/api/v1/customer/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        Login other = toLogin(otherDevice);

        MvcResult changed = mvc.perform(post("/api/v1/customer/auth/change-password")
                .header("Authorization", "Bearer " + device.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"another-long-password\"}")).andReturn();
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        Login fresh = toLogin(changed);

        // The point of changing it: the new password signs in and the old one no longer does. (Found in the
        // 2026-10-06 go-live QA: ending the other sessions cleared the persistence context before the new
        // password was flushed, so the change was silently lost while the request answered 200.)
        assertThat(mvc.perform(post("/api/v1/customer/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn()
                .getResponse().getStatus()).as("the old password").isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/customer/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"another-long-password\"}")).andReturn()
                .getResponse().getStatus()).as("the new password").isEqualTo(200);

        assertThat(refresh(other.refreshCookie()).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(device.refreshCookie()).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(fresh.refreshCookie()).getResponse().getStatus()).isEqualTo(200);
    }
}
