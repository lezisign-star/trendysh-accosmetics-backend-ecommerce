package com.nbh.backend.staff;

import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.staff.dto.StaffDtos.InviteRequest;
import com.nbh.backend.staff.dto.StaffDtos.StaffDto;
import com.nbh.backend.staff.entity.StaffRole;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.staff.service.StaffManagementService;
import com.nbh.backend.staff.service.StaffPasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The who-may-change-whom rules on the Staff tab, tested against mocked repositories so the
 * "last Super Admin" case doesn't depend on whatever accounts other tests left in the shared DB.
 */
class StaffManagementRulesUnitTest {

    private StaffUserRepository staffUsers;
    private StaffRoleRepository staffRoles;
    private StaffManagementService service;

    private StaffRole superAdminRole;
    private StaffRole developerRole;
    private StaffRole cashierRole;

    @BeforeEach
    void setUp() {
        staffUsers = mock(StaffUserRepository.class);
        staffRoles = mock(StaffRoleRepository.class);
        service = new StaffManagementService(staffUsers, staffRoles, mock(PasswordEncoder.class),
                mock(StaffPasswordResetService.class));

        superAdminRole = role("SUPER_ADMIN");
        developerRole = role("DEVELOPER");
        cashierRole = role("CASHIER");
        when(staffRoles.findByName("SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(staffRoles.findByName("DEVELOPER")).thenReturn(Optional.of(developerRole));
        when(staffRoles.findByName("CASHIER")).thenReturn(Optional.of(cashierRole));
        when(staffRoles.findByDeletedFalseOrderByDisplayName())
                .thenReturn(List.of(superAdminRole, developerRole, cashierRole));
        when(staffUsers.save(any(StaffUser.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private StaffRole role(String name) {
        StaffRole r = new StaffRole();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setDisplayName(name);
        return r;
    }

    private StaffUser user(StaffRole role) {
        StaffUser u = new StaffUser();
        u.setId(UUID.randomUUID());
        u.setEmail(role.getName().toLowerCase() + "-" + u.getId() + "@example.com");
        u.setFirstName("Test");
        u.setLastName(role.getName());
        u.setStaffRole(role);
        when(staffUsers.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void developerRoleIsOnlyOfferedToADeveloper() {
        assertThat(service.roles(user(superAdminRole))).extracting("name")
                .containsExactly("SUPER_ADMIN", "CASHIER");
        assertThat(service.roles(user(developerRole))).extracting("name")
                .containsExactly("SUPER_ADMIN", "DEVELOPER", "CASHIER");
    }

    @Test
    void aSuperAdminCannotInviteADeveloper() {
        var request = new InviteRequest("new@example.com", "New", "Dev", "DEVELOPER");
        assertThatThrownBy(() -> service.invite(request, user(superAdminRole)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("Only a developer");
    }

    @Test
    void aSuperAdminCannotPromoteSomeoneToDeveloper() {
        StaffUser cashier = user(cashierRole);
        assertThatThrownBy(() -> service.updateRole(cashier.getId(), "DEVELOPER", user(superAdminRole)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("Only a developer");
        assertThat(cashier.getStaffRole().getName()).isEqualTo("CASHIER");
    }

    @Test
    void aDeveloperMayPromoteSomeoneToDeveloper() {
        StaffUser cashier = user(cashierRole);
        var result = service.updateRole(cashier.getId(), "DEVELOPER", user(developerRole));
        assertThat(result.roleName()).isEqualTo("DEVELOPER");
    }

    @Test
    void nobodyCanChangeTheirOwnRole() {
        StaffUser superAdmin = user(superAdminRole);
        assertThatThrownBy(() -> service.updateRole(superAdmin.getId(), "CASHIER", superAdmin))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("your own role");
        StaffUser cashier = user(cashierRole);
        assertThatThrownBy(() -> service.updateRole(cashier.getId(), "DEVELOPER", cashier))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("your own role");
    }

    @Test
    void theDeveloperAccountCannotBeChangedOrDisabledByASuperAdmin() {
        StaffUser developer = user(developerRole);
        StaffUser superAdmin = user(superAdminRole);
        assertThatThrownBy(() -> service.updateRole(developer.getId(), "CASHIER", superAdmin))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("developer account");
        assertThatThrownBy(() -> service.setEnabled(developer.getId(), false, superAdmin))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("developer account");
        assertThatThrownBy(() -> service.updateName(developer.getId(), "New", "Name", superAdmin))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("developer account");
        assertThat(developer.isEnabled()).isTrue();
        assertThat(developer.getFirstName()).isEqualTo("Test");
    }

    @Test
    void aStaffMembersNameCanBeCorrectedIncludingTheirOwn() {
        StaffUser cashier = user(cashierRole);
        StaffUser superAdmin = user(superAdminRole);

        StaffDto updated = service.updateName(cashier.getId(), "  Jane  ", "  Wanjiru  ", superAdmin);
        assertThat(updated.firstName()).isEqualTo("Jane");
        assertThat(updated.lastName()).isEqualTo("Wanjiru");

        // Unlike role/status, there's no rule against a staff member editing their own name.
        StaffDto ownNameFixed = service.updateName(superAdmin.getId(), "Corrected", "Self", superAdmin);
        assertThat(ownNameFixed.firstName()).isEqualTo("Corrected");
    }

    @Test
    void aDeveloperMayRenameTheDeveloperAccount() {
        StaffUser developer = user(developerRole);
        StaffDto result = service.updateName(developer.getId(), "Renamed", "Developer", developer);
        assertThat(result.firstName()).isEqualTo("Renamed");
    }

    @Test
    void theLastActiveSuperAdminCannotBeDemotedOrDisabled() {
        StaffUser lastSuperAdmin = user(superAdminRole);
        StaffUser developer = user(developerRole);
        when(staffUsers.countByStaffRoleNameAndEnabledTrueAndDeletedFalseAndMustChangePasswordFalseAndIdNot(anyString(), any(UUID.class)))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.updateRole(lastSuperAdmin.getId(), "CASHIER", developer))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("at least one active Super Admin");
        assertThatThrownBy(() -> service.setEnabled(lastSuperAdmin.getId(), false, developer))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("at least one active Super Admin");
        assertThat(lastSuperAdmin.getStaffRole().getName()).isEqualTo("SUPER_ADMIN");
        assertThat(lastSuperAdmin.isEnabled()).isTrue();
    }

    @Test
    void aSuperAdminMayBeDemotedOrDisabledWhenAnotherOneRemains() {
        StaffUser superAdmin = user(superAdminRole);
        StaffUser developer = user(developerRole);
        when(staffUsers.countByStaffRoleNameAndEnabledTrueAndDeletedFalseAndMustChangePasswordFalseAndIdNot(anyString(), any(UUID.class)))
                .thenReturn(1L);

        assertThat(service.setEnabled(superAdmin.getId(), false, developer).status()).isEqualTo("DISABLED");
        superAdmin.setEnabled(true);
        assertThat(service.updateRole(superAdmin.getId(), "CASHIER", developer).roleName()).isEqualTo("CASHIER");
    }

    @Test
    void ordinaryChangesToOrdinaryStaffStillWork() {
        StaffUser cashier = user(cashierRole);
        StaffUser superAdmin = user(superAdminRole);
        assertThat(service.setEnabled(cashier.getId(), false, superAdmin).status()).isEqualTo("DISABLED");
        assertThat(service.updateRole(cashier.getId(), "SUPER_ADMIN", superAdmin).roleName()).isEqualTo("SUPER_ADMIN");
    }
}
