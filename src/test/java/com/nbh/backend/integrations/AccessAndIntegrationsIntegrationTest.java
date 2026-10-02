package com.nbh.backend.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Who may do what with payment details and third-party credentials (Managers can edit the Paybill/bank details
 * but never the keys), how those credentials are stored and shown, and the photo-cleanup guard rails that hang
 * off them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessAndIntegrationsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IntegrationSettingRepository settings;
    @Autowired private IntegrationSettingsService integrationService;

    private String tokenFor(String roleName) {
        if ("SUPER_ADMIN".equals(roleName)) {
            return jwtService.generateAccessToken(staffUsers.findAll().stream()
                    .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                    .findFirst().orElseThrow());
        }
        StaffUser user = new StaffUser();
        user.setEmail(roleName.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode("Irrelevant123!"));
        user.setFirstName(roleName);
        user.setLastName("Tester");
        user.setStaffRole(roles.findByName(roleName).orElseThrow());
        return jwtService.generateAccessToken(staffUsers.save(user));
    }

    private int status(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult r = mvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    @AfterEach
    void removeTestCredentials() {
        for (IntegrationKey key : IntegrationKey.values()) {
            integrationService.clear(key); // also drops the in-memory cache, which deleting rows would not
        }
    }

    // ---- payment details access ----

    @Test
    void aManagerCanReadAndEditPaymentDetails() throws Exception {
        String manager = tokenFor("MANAGER");
        String body = "{\"bankName\":\"KCB\",\"paybillNumber\":\"522522\",\"accountName\":\"Trendysh\",\"accountNumber\":\"1\"}";
        assertThat(ok(put("/api/v1/admin/settings/payment"), manager, body).get("paybillNumber").asText()).isEqualTo("522522");
        assertThat(ok(get("/api/v1/admin/settings/payment"), manager, null).get("bankName").asText()).isEqualTo("KCB");
    }

    @Test
    void aCashierAndAViewerCannotTouchPaymentDetails() throws Exception {
        for (String role : new String[]{"CASHIER", "VIEWER", "STOCK_CONTROLLER"}) {
            String t = tokenFor(role);
            assertThat(status(get("/api/v1/admin/settings/payment"), t, null)).as(role).isEqualTo(403);
            assertThat(status(put("/api/v1/admin/settings/payment"), t, "{\"bankName\":\"x\"}")).as(role).isEqualTo(403);
        }
        assertThat(status(get("/api/v1/admin/settings/payment"), null, null)).isIn(401, 403);
    }

    @Test
    void theManagerRoleHoldsThePaymentSettingsPermissionButNotGeneralSettings() {
        var perms = roles.findByName("MANAGER").orElseThrow().getPermissions().stream().map(Enum::name).toList();
        assertThat(perms).contains("PAYMENT_SETTINGS_MANAGE").doesNotContain("SETTINGS_MANAGE");
    }

    // ---- credentials ----

    @Test
    void credentialsAreOnlyForSettingsManagersAndNeverShownBack() throws Exception {
        assertThat(status(get("/api/v1/admin/settings/integrations"), tokenFor("MANAGER"), null)).isEqualTo(403);
        assertThat(status(put("/api/v1/admin/settings/integrations/GEMINI_API_KEY"), tokenFor("CASHIER"),
                "{\"value\":\"x\"}")).isEqualTo(403);

        String admin = tokenFor("SUPER_ADMIN");
        ok(put("/api/v1/admin/settings/integrations/GEMINI_API_KEY"), admin, "{\"value\":\"AIzaSecretKey1234\"}");
        ok(put("/api/v1/admin/settings/integrations/CLOUDINARY_CLOUD_NAME"), admin, "{\"value\":\"my-cloud\"}");

        MvcResult listed = mvc.perform(get("/api/v1/admin/settings/integrations").header("Authorization", "Bearer " + admin)).andReturn();
        String raw = listed.getResponse().getContentAsString();
        assertThat(raw).doesNotContain("AIzaSecretKey1234");
        JsonNode list = json.readTree(raw);
        for (JsonNode i : list) {
            if ("GEMINI_API_KEY".equals(i.get("key").asText())) {
                assertThat(i.get("hint").asText()).isEqualTo("••••1234");
                assertThat(i.get("source").asText()).isEqualTo("DASHBOARD");
                assertThat(i.get("configured").asBoolean()).isTrue();
            }
            if ("CLOUDINARY_CLOUD_NAME".equals(i.get("key").asText())) {
                assertThat(i.get("hint").asText()).isEqualTo("my-cloud"); // not a secret: shown in full
            }
        }
    }

    @Test
    void storedCredentialsAreEncryptedAndRemovingOneUnsetsIt() throws Exception {
        String admin = tokenFor("SUPER_ADMIN");
        ok(put("/api/v1/admin/settings/integrations/CLOUDINARY_API_SECRET"), admin, "{\"value\":\"plain-secret-value\"}");
        var row = settings.findById("CLOUDINARY_API_SECRET").orElseThrow();
        assertThat(row.getValueEncrypted()).doesNotContain("plain-secret-value");

        JsonNode after = ok(delete("/api/v1/admin/settings/integrations/CLOUDINARY_API_SECRET"), admin, null);
        for (JsonNode i : after) {
            if ("CLOUDINARY_API_SECRET".equals(i.get("key").asText())) assertThat(i.get("configured").asBoolean()).isFalse();
        }
        assertThat(settings.findById("CLOUDINARY_API_SECRET")).isEmpty();
    }

    @Test
    void aBlankCredentialAndAnUnknownKeyAreRejected() throws Exception {
        String admin = tokenFor("SUPER_ADMIN");
        assertThat(status(put("/api/v1/admin/settings/integrations/GEMINI_API_KEY"), admin, "{\"value\":\"   \"}")).isEqualTo(400);
        assertThat(status(put("/api/v1/admin/settings/integrations/NOT_A_KEY"), admin, "{\"value\":\"x\"}")).isIn(400, 404);
    }

    // ---- photo cleanup guard rails ----

    @Test
    void photoCleanupRefusesToRunUntilBothServicesAreConfiguredThenAcceptsTheirKeys() throws Exception {
        String admin = tokenFor("SUPER_ADMIN");
        JsonNode before = ok(get("/api/v1/admin/image-cleanup/status"), admin, null);
        assertThat(before.get("geminiConfigured").asBoolean()).isFalse();
        assertThat(before.get("maxImagesPerProduct").asInt()).isEqualTo(6);
        assertThat(status(post("/api/v1/admin/image-cleanup/run"), admin, "{\"limit\":1,\"imagesPerProduct\":3}")).isEqualTo(422);

        ok(put("/api/v1/admin/settings/integrations/GEMINI_API_KEY"), admin, "{\"value\":\"AIzaTestKey\"}");
        JsonNode mid = ok(get("/api/v1/admin/image-cleanup/status"), admin, null);
        assertThat(mid.get("geminiConfigured").asBoolean()).isTrue();
        assertThat(mid.get("cloudinaryConfigured").asBoolean()).isFalse();
        // Gemini alone is not enough: cleaned photos would have nowhere to go.
        assertThat(status(post("/api/v1/admin/image-cleanup/run"), admin, "{\"limit\":1,\"imagesPerProduct\":3}")).isEqualTo(422);
    }

    @Test
    void imagesPerProductIsBoundedAndCleanupNeedsProductManagement() throws Exception {
        String admin = tokenFor("SUPER_ADMIN");
        assertThat(status(post("/api/v1/admin/image-cleanup/run"), admin, "{\"limit\":1,\"imagesPerProduct\":0}")).isEqualTo(400);
        assertThat(status(post("/api/v1/admin/image-cleanup/run"), admin, "{\"limit\":1,\"imagesPerProduct\":7}")).isEqualTo(400);
        assertThat(status(post("/api/v1/admin/image-cleanup/run"), tokenFor("CASHIER"), "{\"limit\":1,\"imagesPerProduct\":3}")).isEqualTo(403);
        assertThat(status(get("/api/v1/admin/image-cleanup/status"), tokenFor("VIEWER"), null)).isEqualTo(403);
    }
}
