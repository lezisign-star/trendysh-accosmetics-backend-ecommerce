package com.nbh.backend.staff;

import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff use the same forgot/reset-password pages as shoppers. A staff email gets a staff reset code;
 * the code is single-use, locks out after 5 wrong guesses, and an unknown email behaves exactly like
 * a known one from the outside. Not @Transactional: emails go out after commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffPasswordResetIntegrationTest {

    /** The code sits alone on its own line in the text body, between the intro line and the expiry note. */
    private static final Pattern OTP_CODE = Pattern.compile("\\n(\\d{6})\\n");

    @Autowired private MockMvc mvc;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailSender emailSender;

    private String newStaffEmail() {
        String email = "staff-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        StaffUser user = new StaffUser();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("unknown-random-password"));
        user.setFirstName("Test");
        user.setLastName("Staff");
        user.setStaffRole(roles.findByName("SUPER_ADMIN").orElseThrow());
        user.setMustChangePassword(true);
        staffUsers.save(user);
        return email;
    }

    private void post(String path, String body, int expectedStatus) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    /** Requests a code and returns the most recently emailed one (a test may request more than once). */
    private String requestOtpCode(String email) throws Exception {
        post("/api/v1/customer/auth/forgot-password", "{\"email\":\"" + email + "\"}", 200);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        // The OTP flow always has an HTML alternative, so the 4-arg overload is the one actually called.
        verify(emailSender, atLeastOnce()).send(eq(email), anyString(), body.capture(), anyString());
        List<String> bodies = body.getAllValues();
        Matcher m = OTP_CODE.matcher(bodies.get(bodies.size() - 1));
        assertThat(m.find()).as("email contains a 6-digit code").isTrue();
        return m.group(1);
    }

    private String resetBody(String email, String code, String newPassword) {
        return "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    @Test
    void aStaffMemberCanSetAPasswordThroughTheEmailedCodeAndThenSignIn() throws Exception {
        String email = newStaffEmail();
        String code = requestOtpCode(email);

        post("/api/v1/customer/auth/reset-password", resetBody(email, code, "my-new-admin-password"), 200);

        // Signs in to the staff endpoint with the new password, and is no longer forced to change it.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"my-new-admin-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void theCodeWorksOnlyOnce() throws Exception {
        String email = newStaffEmail();
        String code = requestOtpCode(email);
        post("/api/v1/customer/auth/reset-password", resetBody(email, code, "first-new-password"), 200);
        post("/api/v1/customer/auth/reset-password", resetBody(email, code, "second-new-password"), 422);
    }

    @Test
    void staffPasswordsNeedAtLeastTenCharacters() throws Exception {
        String email = newStaffEmail();
        String code = requestOtpCode(email);
        // 8 chars passes the shopper rule but not the staff one, and does not burn the code.
        post("/api/v1/customer/auth/reset-password", resetBody(email, code, "12345678"), 422);
        post("/api/v1/customer/auth/reset-password", resetBody(email, code, "long-enough-password"), 200);
    }

    @Test
    void anUnknownEmailLooksIdenticalAndSendsNothing() throws Exception {
        String stranger = "nobody-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        post("/api/v1/customer/auth/forgot-password", "{\"email\":\"" + stranger + "\"}", 200);
        verifyNoInteractions(emailSender);
    }

    @Test
    void wrongCodeGuessesAreCountedAndLockOutAfterFive() throws Exception {
        String email = newStaffEmail();
        String code = requestOtpCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 4; i++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/customer/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(resetBody(email, wrong, "SomePassword123")))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.error").value("Incorrect code — " + (4 - i) + " attempt" + (4 - i == 1 ? "" : "s") + " left"));
        }
        // 5th wrong guess locks the code out entirely.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/customer/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody(email, wrong, "SomePassword123")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value("Too many incorrect attempts — please request a new code"));

        // Even the CORRECT code is now rejected — the lockout burns the code, not just the guess count.
        post("/api/v1/customer/auth/reset-password", resetBody(email, code, "SomePassword123"), 422);
    }

    @Test
    void theSeededShopOwnerSuperAdminExists() {
        StaffUser user = staffUsers.findByEmailIgnoreCaseAndDeletedFalse("trendyshaccosmetic@gmail.com").orElseThrow();
        assertThat(user.getStaffRole().getName()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void theSeededDeveloperAccountExistsUnderTheDeveloperRole() {
        StaffUser user = staffUsers.findByEmailIgnoreCaseAndDeletedFalse("mdaucodes@gmail.com").orElseThrow();
        assertThat(user.getStaffRole().getName()).isEqualTo("DEVELOPER");
    }

    @Test
    void theDeveloperRoleHasTheSamePermissionCountAsSuperAdmin() {
        assertThat(roles.findByName("DEVELOPER").orElseThrow().getPermissions())
                .isEqualTo(roles.findByName("SUPER_ADMIN").orElseThrow().getPermissions());
    }
}
