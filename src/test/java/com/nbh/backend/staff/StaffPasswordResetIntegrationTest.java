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

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff use the same forgot/reset-password pages as shoppers. A staff email gets a staff reset link;
 * the link works once, sets a real password (clearing the must-change flag), and an unknown email
 * behaves exactly like a known one from the outside. Not @Transactional: emails go out after commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffPasswordResetIntegrationTest {

    private static final Pattern LINK_TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

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

    private String requestLinkToken(String email) throws Exception {
        post("/api/v1/customer/auth/forgot-password", "{\"email\":\"" + email + "\"}", 200);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(email), anyString(), body.capture());
        Matcher m = LINK_TOKEN.matcher(body.getValue());
        assertThat(m.find()).as("email contains a reset link").isTrue();
        return m.group(1);
    }

    @Test
    void aStaffMemberCanSetAPasswordThroughTheEmailedLinkAndThenSignIn() throws Exception {
        String email = newStaffEmail();
        String token = requestLinkToken(email);

        post("/api/v1/customer/auth/reset-password",
                "{\"token\":\"" + token + "\",\"newPassword\":\"my-new-admin-password\"}", 200);

        // Signs in to the staff endpoint with the new password, and is no longer forced to change it.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"my-new-admin-password\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void theLinkWorksOnlyOnce() throws Exception {
        String token = requestLinkToken(newStaffEmail());
        post("/api/v1/customer/auth/reset-password",
                "{\"token\":\"" + token + "\",\"newPassword\":\"first-new-password\"}", 200);
        post("/api/v1/customer/auth/reset-password",
                "{\"token\":\"" + token + "\",\"newPassword\":\"second-new-password\"}", 422);
    }

    @Test
    void staffPasswordsNeedAtLeastTenCharacters() throws Exception {
        String token = requestLinkToken(newStaffEmail());
        // 8 chars passes the shopper rule but not the staff one, and does not burn the link.
        post("/api/v1/customer/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"12345678\"}", 422);
        post("/api/v1/customer/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"long-enough-password\"}", 200);
    }

    @Test
    void anUnknownEmailLooksIdenticalAndSendsNothing() throws Exception {
        String stranger = "nobody-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        post("/api/v1/customer/auth/forgot-password", "{\"email\":\"" + stranger + "\"}", 200);
        verify(emailSender, never()).send(eq(stranger), anyString(), anyString());
    }

    @Test
    void theSeededOwnerAdminExists() {
        assertThat(staffUsers.existsByEmailIgnoreCase("mdaucodes@gmail.com")).isTrue();
    }
}
