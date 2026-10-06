package com.nbh.backend.staff;

import com.nbh.backend.common.email.EmailSender;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.entity.StaffRole;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin Staff tab endpoints: inviting, listing, role changes and disable/reactivate. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffManagementIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailSender emailSender;

    private String superAdminToken;
    private StaffUser superAdmin;

    @BeforeEach
    void setUp() {
        superAdmin = staffUsers.findByEmailIgnoreCaseAndDeletedFalse("admin@nairobibeautyhub.co.ke").orElseThrow();
        superAdminToken = jwtService.generateAccessToken(superAdmin);
    }

    private String uniqueEmail() {
        return "invitee-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** MANAGER has USER_VIEW but not USER_CREATE — can see the staff list, can't invite. */
    private String managerToken() {
        StaffUser manager = new StaffUser();
        manager.setEmail("manager-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        manager.setPassword(passwordEncoder.encode("irrelevant-password"));
        manager.setFirstName("Branch");
        manager.setLastName("Manager");
        manager.setStaffRole(staffRoles.findByName("MANAGER").orElseThrow());
        staffUsers.save(manager);
        return jwtService.generateAccessToken(manager);
    }

    @Test
    void endpointsRequireAStaffTokenAndNoTokenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/admin/staff")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/staff/invite").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRoleWithoutUserCreateCannotInviteButCanStillList() throws Exception {
        String token = managerToken();
        mvc.perform(get("/api/v1/admin/staff").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/admin/staff/invite").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nope@example.com\",\"firstName\":\"A\",\"lastName\":\"B\",\"roleName\":\"CASHIER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invitingCreatesAPendingStaffMemberAndEmailsAnOtpCode() throws Exception {
        String email = uniqueEmail();

        mvc.perform(post("/api/v1/admin/staff/invite").header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"firstName\":\"Amina\",\"lastName\":\"Otieno\","
                                + "\"roleName\":\"CASHIER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roleName").value("CASHIER"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(emailSender, atLeastOnce()).send(eq(email), any(), any(), any());

        mvc.perform(get("/api/v1/admin/staff").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email=='" + email + "')].status").value("PENDING"));
    }

    @Test
    void invitingTheSameEmailTwiceConflicts() throws Exception {
        String email = uniqueEmail();
        String body = "{\"email\":\"" + email + "\",\"firstName\":\"A\",\"lastName\":\"B\",\"roleName\":\"CASHIER\"}";

        mvc.perform(post("/api/v1/admin/staff/invite").header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/staff/invite").header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void changingRoleAndDisablingThenReactivatingWorkThroughTheApi() throws Exception {
        StaffUser target = new StaffUser();
        target.setEmail(uniqueEmail());
        target.setPassword(passwordEncoder.encode("irrelevant-password"));
        target.setFirstName("Target");
        target.setLastName("Staffer");
        target.setStaffRole(staffRoles.findByName("CASHIER").orElseThrow());
        target.setMustChangePassword(true); // simulates an invited-but-never-signed-in account
        target = staffUsers.save(target);

        mvc.perform(patch("/api/v1/admin/staff/" + target.getId() + "/role")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleName\":\"STOCK_CONTROLLER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleName").value("STOCK_CONTROLLER"));

        mvc.perform(patch("/api/v1/admin/staff/" + target.getId() + "/status")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mvc.perform(patch("/api/v1/admin/staff/" + target.getId() + "/status")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")); // still never signed in
    }

    @Test
    void aStaffMembersNameCanBeCorrectedThroughTheApiIncludingTheirOwn() throws Exception {
        StaffUser target = staffWithRole("CASHIER");

        mvc.perform(patch("/api/v1/admin/staff/" + target.getId() + "/name")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Jane\",\"lastName\":\"Wanjiru\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Jane"))
                .andExpect(jsonPath("$.lastName").value("Wanjiru"));

        // Unlike /role and /status, there is no rule against editing your own name.
        mvc.perform(patch("/api/v1/admin/staff/" + superAdmin.getId() + "/name")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Corrected\",\"lastName\":\"Self\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Corrected"));
    }

    @Test
    void renamingRequiresUserManageRolesJustLikeRoleAndStatus() throws Exception {
        String managerToken = managerToken(); // has USER_VIEW, not USER_MANAGE_ROLES
        StaffUser target = staffWithRole("CASHIER");

        mvc.perform(patch("/api/v1/admin/staff/" + target.getId() + "/name")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Nope\",\"lastName\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSuperAdminCannotRenameTheDeveloperAccount() throws Exception {
        StaffUser developer = staffWithRole("DEVELOPER");

        mvc.perform(patch("/api/v1/admin/staff/" + developer.getId() + "/name")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Nope\",\"lastName\":\"Nope\"}"))
                .andExpect(status().is(422));
    }

    @Test
    void aStaffMemberCannotDisableTheirOwnAccount() throws Exception {
        mvc.perform(patch("/api/v1/admin/staff/" + superAdmin.getId() + "/status")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().is(422));
    }

    private StaffUser staffWithRole(String role) {
        StaffUser user = new StaffUser();
        user.setEmail(role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode("irrelevant-password"));
        user.setFirstName("Test");
        user.setLastName(role);
        user.setStaffRole(staffRoles.findByName(role).orElseThrow());
        return staffUsers.save(user);
    }

    @Test
    void theDeveloperRoleIsOnlyOfferedToADeveloper() throws Exception {
        mvc.perform(get("/api/v1/admin/staff/roles").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='DEVELOPER')]").isEmpty());

        String developerToken = jwtService.generateAccessToken(staffWithRole("DEVELOPER"));
        mvc.perform(get("/api/v1/admin/staff/roles").header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='DEVELOPER')]").exists());
    }

    @Test
    void aSuperAdminCannotInviteAnAccountAsDeveloper() throws Exception {
        mvc.perform(post("/api/v1/admin/staff/invite").header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail() + "\",\"firstName\":\"A\",\"lastName\":\"B\","
                                + "\"roleName\":\"DEVELOPER\"}"))
                .andExpect(status().is(422));
    }

    @Test
    void aStaffMemberCannotChangeTheirOwnRole() throws Exception {
        mvc.perform(patch("/api/v1/admin/staff/" + superAdmin.getId() + "/role")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleName\":\"CASHIER\"}"))
                .andExpect(status().is(422));
    }

    @Test
    void aSuperAdminCannotChangeOrDisableTheDeveloperAccount() throws Exception {
        StaffUser developer = staffWithRole("DEVELOPER");

        mvc.perform(patch("/api/v1/admin/staff/" + developer.getId() + "/role")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleName\":\"CASHIER\"}"))
                .andExpect(status().is(422));
        mvc.perform(patch("/api/v1/admin/staff/" + developer.getId() + "/status")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().is(422));
    }

    /**
     * "At least one active Super Admin" has to mean someone who can really sign in. Found in the 2026-10-06 go-live
     * QA: the never-used placeholder admin (and any invitee who had not yet set a password) counted as "another Super
     * Admin", so the real owner could be demoted with nobody left able to run the shop. The count is relative because
     * the other tests in this class share the database and create their own Super Admins.
     */
    @Test
    void onlyASuperAdminWhoCanSignInCountsAsAnotherOwner() {
        UUID nobody = UUID.randomUUID();
        long before = staffUsers.countByStaffRoleNameAndEnabledTrueAndDeletedFalseAndMustChangePasswordFalseAndIdNot("SUPER_ADMIN", nobody);

        StaffUser neverSignedIn = staffWithRole("SUPER_ADMIN");
        neverSignedIn.setMustChangePassword(true);
        staffUsers.save(neverSignedIn);
        assertThat(staffUsers.countByStaffRoleNameAndEnabledTrueAndDeletedFalseAndMustChangePasswordFalseAndIdNot("SUPER_ADMIN", nobody))
                .as("an invited owner who has not set a password yet").isEqualTo(before);

        StaffUser switchedOff = staffWithRole("SUPER_ADMIN");
        switchedOff.setEnabled(false);
        staffUsers.save(switchedOff);
        assertThat(staffUsers.countByStaffRoleNameAndEnabledTrueAndDeletedFalseAndMustChangePasswordFalseAndIdNot("SUPER_ADMIN", nobody))
                .as("a disabled owner").isEqualTo(before);

        StaffUser working = staffWithRole("SUPER_ADMIN");
        assertThat(staffUsers.countByStaffRoleNameAndEnabledTrueAndDeletedFalseAndMustChangePasswordFalseAndIdNot("SUPER_ADMIN", nobody))
                .as("an owner who is enabled and has signed in").isEqualTo(before + 1);
        assertThat(staffUsers.countByStaffRoleNameAndEnabledTrueAndDeletedFalseAndMustChangePasswordFalseAndIdNot("SUPER_ADMIN", working.getId()))
                .as("…but never counted against themselves").isEqualTo(before);
    }

    @Test
    void listRolesReturnsTheSeededDefaults() throws Exception {
        mvc.perform(get("/api/v1/admin/staff/roles").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='CASHIER')]").exists())
                .andExpect(jsonPath("$[?(@.name=='SUPER_ADMIN')]").exists());
    }
}
