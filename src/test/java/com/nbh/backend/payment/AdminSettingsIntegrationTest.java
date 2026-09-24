package com.nbh.backend.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The Settings tab's real (backend-persisted) half: an admin can read and update the bank/paybill/
 * WhatsApp details a customer sees at checkout, gated to SUPER_ADMIN/DEVELOPER-level access
 * (PERM_SETTINGS_MANAGE), and a blank field clears it (hides that payment method on the pay page —
 * see PaymentInstructionsService/BankPaymentPageIntegrationTest for that half of the contract).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSettingsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;

    private String superAdminToken;

    @BeforeEach
    void signInAsSuperAdmin() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        superAdminToken = jwtService.generateAccessToken(admin);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body, String token) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    @Test
    void updatingThenReadingReturnsExactlyWhatWasSaved() throws Exception {
        String body = "{\"bankName\":\"KCB\",\"paybillNumber\":\"522522\",\"accountName\":\"Trendysh Ltd\","
                + "\"accountNumber\":\"1234567890\",\"accountBranch\":\"Westlands\",\"supportWhatsapp\":\"254711000111\"}";

        JsonNode updated = ok(put("/api/v1/admin/settings/payment"), body, superAdminToken);
        assertThat(updated.get("bankName").asText()).isEqualTo("KCB");
        assertThat(updated.get("paybillNumber").asText()).isEqualTo("522522");

        JsonNode fetched = ok(get("/api/v1/admin/settings/payment"), null, superAdminToken);
        assertThat(fetched.get("bankName").asText()).isEqualTo("KCB");
        assertThat(fetched.get("accountBranch").asText()).isEqualTo("Westlands");
        assertThat(fetched.get("supportWhatsapp").asText()).isEqualTo("254711000111");
    }

    @Test
    void aBlankFieldClearsItRatherThanBeingRejected() throws Exception {
        ok(put("/api/v1/admin/settings/payment"),
                "{\"bankName\":\"Equity\",\"paybillNumber\":\"247247\",\"accountName\":\"x\",\"accountNumber\":\"1\"}",
                superAdminToken);

        JsonNode cleared = ok(put("/api/v1/admin/settings/payment"),
                "{\"bankName\":\"Equity\",\"paybillNumber\":\"\",\"accountName\":\"\",\"accountNumber\":\"\"}",
                superAdminToken);

        assertThat(cleared.get("bankName").asText()).isEqualTo("Equity");
        assertThat(cleared.get("paybillNumber").isNull()).isTrue();
        assertThat(cleared.get("accountNumber").isNull()).isTrue();
    }

    @Test
    void aRoleWithoutSettingsManageIsForbidden() throws Exception {
        StaffUser manager = new StaffUser();
        manager.setEmail("settings-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        manager.setPassword(passwordEncoder.encode("irrelevant-password"));
        manager.setFirstName("Branch");
        manager.setLastName("Manager");
        manager.setStaffRole(staffRoles.findByName("MANAGER").orElseThrow());
        staffUsers.save(manager);
        String token = jwtService.generateAccessToken(manager);

        mvc.perform(get("/api/v1/admin/settings/payment").header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
}
