package com.nbh.backend.shop;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * How the shop takes orders (owner, 2026-10-21): the storefront reads the mode without signing in; only the owner
 * (SUPER_ADMIN) and the developer can change it. Same Spring context as the other integration tests (identical annotations).
 * The one row is shared by every test in the run, so each test puts it back to the starting mode when it is done.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShopModeIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ShopSettingsService settings;

    private String tokenFor(String role) {
        StaffUser user = new StaffUser();
        user.setEmail(role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode("irrelevant-password"));
        user.setFirstName(role.substring(0, 1) + role.substring(1).toLowerCase());
        user.setLastName("Tester");
        user.setStaffRole(staffRoles.findByName(role).orElseThrow());
        return jwtService.generateAccessToken(staffUsers.save(user));
    }

    private MvcResult change(String bearer, String mode) throws Exception {
        return mvc.perform(put("/api/v1/admin/shop-mode").header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"orderingMode\":\"" + mode + "\"}")).andReturn();
    }

    private String publicMode() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/catalog/shop-mode")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).get("orderingMode").asText();
    }

    @AfterEach
    void backToTheStartingMode() {
        settings.setOrderingMode(OrderingMode.WHATSAPP, null);
    }

    @Test
    void aNewShopTakesOrdersOnWhatsAppAndTheStorefrontReadsThatWithoutSigningIn() throws Exception {
        assertThat(publicMode()).isEqualTo("WHATSAPP");
    }

    @Test
    void theOwnerCanSwitchToFullOrderingAndBackAndTheStorefrontSeesItAtOnce() throws Exception {
        String owner = tokenFor("SUPER_ADMIN");

        MvcResult toFull = change(owner, "FULL");
        assertThat(toFull.getResponse().getStatus()).isEqualTo(200);
        JsonNode answered = json.readTree(toFull.getResponse().getContentAsString());
        assertThat(answered.get("orderingMode").asText()).isEqualTo("FULL");
        assertThat(answered.get("changedBy").asText()).isEqualTo("Super_admin Tester");
        assertThat(answered.get("changedAt").isNull()).isFalse();
        assertThat(publicMode()).isEqualTo("FULL");

        assertThat(change(owner, "WHATSAPP").getResponse().getStatus()).isEqualTo(200);
        assertThat(publicMode()).isEqualTo("WHATSAPP");
    }

    @Test
    void theDeveloperCanChangeItToo() throws Exception {
        assertThat(change(tokenFor("DEVELOPER"), "FULL").getResponse().getStatus()).isEqualTo(200);
        assertThat(publicMode()).isEqualTo("FULL");
    }

    @Test
    void theSettingsScreenReadsWhoLastChangedItAndOnlyTheOwnerAndTheDeveloperMay() throws Exception {
        String owner = tokenFor("SUPER_ADMIN");
        change(owner, "FULL");

        MvcResult read = mvc.perform(get("/api/v1/admin/shop-mode").header("Authorization", "Bearer " + owner)).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(read.getResponse().getContentAsString());
        assertThat(body.get("orderingMode").asText()).isEqualTo("FULL");
        assertThat(body.get("changedBy").asText()).isNotBlank();

        for (String role : new String[]{"MANAGER", "CASHIER", "VIEWER", "STOCK_CONTROLLER"}) {
            MvcResult refused = mvc.perform(get("/api/v1/admin/shop-mode")
                    .header("Authorization", "Bearer " + tokenFor(role))).andReturn();
            assertThat(refused.getResponse().getStatus()).as(role + " reads it").isEqualTo(403);
        }
        assertThat(mvc.perform(get("/api/v1/admin/shop-mode")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    @Test
    void aManagerOrAnyoneElseCannotChangeItAndNothingMoves() throws Exception {
        for (String role : new String[]{"MANAGER", "CASHIER", "VIEWER", "STOCK_CONTROLLER"}) {
            assertThat(change(tokenFor(role), "FULL").getResponse().getStatus()).as(role).isEqualTo(403);
        }
        assertThat(publicMode()).isEqualTo("WHATSAPP");
        MvcResult anonymous = mvc.perform(put("/api/v1/admin/shop-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderingMode\":\"FULL\"}")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isIn(401, 403);
        assertThat(publicMode()).isEqualTo("WHATSAPP");
    }

    @Test
    void anUnknownOrMissingModeIsRefused() throws Exception {
        String owner = tokenFor("SUPER_ADMIN");
        assertThat(change(owner, "EVERYTHING").getResponse().getStatus()).isEqualTo(400);
        MvcResult missing = mvc.perform(put("/api/v1/admin/shop-mode").header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(publicMode()).isEqualTo("WHATSAPP");
    }

    @Test
    void sayingTheModeItAlreadyHasChangesNothing() throws Exception {
        String owner = tokenFor("SUPER_ADMIN");
        change(owner, "FULL");
        String firstChanger = json.readTree(mvc.perform(get("/api/v1/admin/shop-mode")
                .header("Authorization", "Bearer " + owner)).andReturn().getResponse().getContentAsString())
                .get("changedBy").asText();

        // another person says FULL again: it is already FULL, so the record of who changed it stays as it was
        MvcResult again = change(tokenFor("DEVELOPER"), "FULL");
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(again.getResponse().getContentAsString()).get("changedBy").asText()).isEqualTo(firstChanger);
    }
}
