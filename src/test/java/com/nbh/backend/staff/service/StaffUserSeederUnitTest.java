package com.nbh.backend.staff.service;

import com.nbh.backend.staff.entity.StaffRole;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StaffUserSeeder}'s bootstrap logic, mocked — no database, no Spring context. Covers the
 * two DEVELOPER-role additions: seeding under a second role list, and the one-time move of a
 * pre-existing account (mdaucodes@gmail.com under the old SUPER_ADMIN default, see V18) onto it.
 * The integration test in {@code StaffPasswordResetIntegrationTest} covers the same behaviour
 * end to end through a real boot; this class isolates the branching logic itself.
 */
@ExtendWith(MockitoExtension.class)
class StaffUserSeederUnitTest {

    @Mock private StaffUserRepository staffUsers;
    @Mock private StaffRoleRepository staffRoles;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StaffRoleSeeder staffRoleSeederForBootOrdering;

    private StaffUserSeeder seeder;
    private StaffRole superAdmin;
    private StaffRole developer;

    @BeforeEach
    void setUp() {
        seeder = new StaffUserSeeder(staffUsers, staffRoles, passwordEncoder, staffRoleSeederForBootOrdering);
        ReflectionTestUtils.setField(seeder, "bootstrapEmail", "admin@nairobibeautyhub.co.ke");
        ReflectionTestUtils.setField(seeder, "additionalAdminEmails", "");
        ReflectionTestUtils.setField(seeder, "developerEmails", "");
        // The bootstrap primary-admin block runs unconditionally unless it already exists — keep
        // every test focused on just the behaviour under test by treating it as already seeded.
        when(staffUsers.existsByEmailIgnoreCase("admin@nairobibeautyhub.co.ke")).thenReturn(true);

        superAdmin = new StaffRole();
        superAdmin.setName("SUPER_ADMIN");
        developer = new StaffRole();
        developer.setName("DEVELOPER");
    }

    @Test
    void movesAPreExistingMdaucodesAccountFromSuperAdminToDeveloper() {
        StaffUser existing = new StaffUser();
        existing.setEmail("mdaucodes@gmail.com");
        existing.setStaffRole(superAdmin);
        when(staffUsers.findByEmailIgnoreCaseAndDeletedFalse("mdaucodes@gmail.com")).thenReturn(Optional.of(existing));
        when(staffRoles.findByName("DEVELOPER")).thenReturn(Optional.of(developer));

        seeder.seed();

        assertThat(existing.getStaffRole()).isEqualTo(developer);
        verify(staffUsers).save(existing);
    }

    @Test
    void leavesAnAlreadyDeveloperMdaucodesAccountUntouched() {
        StaffUser existing = new StaffUser();
        existing.setEmail("mdaucodes@gmail.com");
        existing.setStaffRole(developer);
        when(staffUsers.findByEmailIgnoreCaseAndDeletedFalse("mdaucodes@gmail.com")).thenReturn(Optional.of(existing));

        seeder.seed();

        verify(staffUsers, never()).save(existing);
    }

    @Test
    void doesNothingWhenMdaucodesHasNeverBeenSeeded() {
        when(staffUsers.findByEmailIgnoreCaseAndDeletedFalse("mdaucodes@gmail.com")).thenReturn(Optional.empty());

        seeder.seed();

        verify(staffUsers, never()).save(any(StaffUser.class));
    }

    @Test
    void seedsANewAccountUnderTheDeveloperRoleFromDeveloperEmails() {
        ReflectionTestUtils.setField(seeder, "developerEmails", "new-dev@example.com");
        when(staffUsers.findByEmailIgnoreCaseAndDeletedFalse("mdaucodes@gmail.com")).thenReturn(Optional.empty());
        when(staffUsers.existsByEmailIgnoreCase("new-dev@example.com")).thenReturn(false);
        when(staffRoles.findByName("DEVELOPER")).thenReturn(Optional.of(developer));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        seeder.seed();

        var captor = org.mockito.ArgumentCaptor.forClass(StaffUser.class);
        verify(staffUsers).save(captor.capture());
        StaffUser saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("new-dev@example.com");
        assertThat(saved.getStaffRole()).isEqualTo(developer);
        assertThat(saved.isMustChangePassword()).isTrue();
    }

    @Test
    void skipsADeveloperEmailThatAlreadyHasAnAccount() {
        ReflectionTestUtils.setField(seeder, "developerEmails", "already-here@example.com");
        when(staffUsers.findByEmailIgnoreCaseAndDeletedFalse("mdaucodes@gmail.com")).thenReturn(Optional.empty());
        when(staffUsers.existsByEmailIgnoreCase("already-here@example.com")).thenReturn(true);

        seeder.seed();

        verify(staffUsers, never()).save(any(StaffUser.class));
    }
}
