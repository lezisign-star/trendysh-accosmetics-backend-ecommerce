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

    /** BRANCH_MANAGER has USER_VIEW but not USER_CREATE — can see the staff list, can't invite. */
    private String branchManagerToken() {
        StaffUser manager = new StaffUser();
        manager.setEmail("manager-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        manager.setPassword(passwordEncoder.encode("irrelevant-password"));
        manager.setFirstName("Branch");
        manager.setLastName("Manager");
        manager.setStaffRole(staffRoles.findByName("BRANCH_MANAGER").orElseThrow());
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
        String token = branchManagerToken();
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
    void aStaffMemberCannotDisableTheirOwnAccount() throws Exception {
        mvc.perform(patch("/api/v1/admin/staff/" + superAdmin.getId() + "/status")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().is(422));
    }

    @Test
    void listRolesReturnsTheSeededDefaults() throws Exception {
        mvc.perform(get("/api/v1/admin/staff/roles").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='CASHIER')]").exists())
                .andExpect(jsonPath("$[?(@.name=='SUPER_ADMIN')]").exists());
    }
}
