package com.nbh.backend.staff.service;

import com.nbh.backend.staff.entity.StaffRole;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Creates the first SUPER_ADMIN staff account on first boot only, if the given
 * bootstrap email doesn't already exist. The generated temp password is logged
 * once (not stored anywhere else) — the account is flagged mustChangePassword
 * so it must be rotated on first login.
 *
 * Depends on {@link StaffRoleSeeder} purely to force Spring to fully construct
 * and @PostConstruct-initialise it first — @PostConstruct order is NOT
 * guaranteed across unrelated beans, only within a bean's own dependency
 * chain, and SUPER_ADMIN must already exist before this runs.
 */
@Slf4j
@Component
public class StaffUserSeeder {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
    private final SecureRandom random = new SecureRandom();

    private final StaffUserRepository staffUserRepository;
    private final StaffRoleRepository staffRoleRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Blank (the default) creates no placeholder account. It used to default to admin@nairobibeautyhub.co.ke, an
     * address on a domain nobody here owns (it does not even resolve in DNS, so anyone could register it, receive the
     * "forgot your password" code and become Super Admin), created in every environment including production.
     * Real owners come from {@link #additionalAdminEmails}; set this only for an address the team controls.
     */
    @Value("${app.bootstrap.admin-email:}")
    private String bootstrapEmail;

    /**
     * Comma-separated. Each gets a SUPER_ADMIN account if it doesn't exist yet, with a random password
     * that is never shown anywhere: the owner sets their own through "Forgot your password?" on the
     * sign-in page (a staff reset code is emailed).
     */
    @Value("${app.bootstrap.additional-admin-emails:}")
    private String additionalAdminEmails;

    /**
     * Comma-separated. Same as {@link #additionalAdminEmails} but under the DEVELOPER role instead
     * of SUPER_ADMIN — functionally identical permissions, kept as a separate role purely so an
     * engineering account reads distinctly from the shop owner's in the staff list.
     */
    @Value("${app.bootstrap.developer-emails:}")
    private String developerEmails;

    public StaffUserSeeder(StaffUserRepository staffUserRepository,
                            StaffRoleRepository staffRoleRepository,
                            PasswordEncoder passwordEncoder,
                            StaffRoleSeeder staffRoleSeederForBootOrdering) {
        this.staffUserRepository = staffUserRepository;
        this.staffRoleRepository = staffRoleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void seed() {
        // One-time move for the account seeded under the old SUPER_ADMIN default (see V18) before
        // DEVELOPER existed as a role — seedEmails() below only creates MISSING accounts, so on any
        // environment that already has this row (dev, test, Railway production), simply re-pointing
        // developer-emails at it wouldn't change its existing role. Idempotent: a no-op once moved.
        moveToRoleIfPresent("mdaucodes@gmail.com", "DEVELOPER");
        // That move fixed the ROLE but not the display name — this account was originally seeded
        // via seedEmails(..., "Owner", "Admin") below, back when it was the SUPER_ADMIN default, so
        // "Signed in as Owner Admin" / "Welcome, Owner" still showed on every admin screen despite
        // the role already correctly reading DEVELOPER underneath. Client's own SUPER_ADMIN account
        // keeps "Owner" — only this specific known-stale placeholder is touched, so a name changed
        // later through the account's own profile settings is never overwritten.
        renameIfStillDefault("mdaucodes@gmail.com", "Owner", "Admin", "Developer", "Account");

        seedEmails(additionalAdminEmails, "SUPER_ADMIN", "Owner", "Admin");
        seedEmails(developerEmails, "DEVELOPER", "Developer", "Account");
        if (bootstrapEmail == null || bootstrapEmail.isBlank()
                || staffUserRepository.existsByEmailIgnoreCase(bootstrapEmail)) {
            return;
        }

        StaffRole superAdmin = staffRoleRepository.findByName("SUPER_ADMIN")
                .orElseThrow(() -> new IllegalStateException("SUPER_ADMIN role must be seeded before StaffUserSeeder runs"));

        String tempPassword = generateTempPassword();

        StaffUser user = new StaffUser();
        user.setEmail(bootstrapEmail);
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setFirstName("Super");
        user.setLastName("Admin");
        user.setStaffRole(superAdmin);
        user.setMustChangePassword(true);
        staffUserRepository.save(user);

        log.warn("""

                ================================================================
                Bootstrap SUPER_ADMIN account created.
                Email:    {}
                Password: {}
                This is logged ONCE. Log in immediately and change the password
                — mustChangePassword is set, so the frontend must force this.
                ================================================================""",
                bootstrapEmail, tempPassword);
    }

    private void moveToRoleIfPresent(String email, String roleName) {
        staffUserRepository.findByEmailIgnoreCaseAndDeletedFalse(email).ifPresent(user -> {
            if (!roleName.equals(user.getStaffRole().getName())) {
                StaffRole role = staffRoleRepository.findByName(roleName)
                        .orElseThrow(() -> new IllegalStateException(roleName + " role must be seeded before StaffUserSeeder runs"));
                String previous = user.getStaffRole().getName();
                user.setStaffRole(role);
                staffUserRepository.save(user);
                log.info("Moved {} from {} to {}", email, previous, roleName);
            }
        });
    }

    private void renameIfStillDefault(String email, String oldFirst, String oldLast, String newFirst, String newLast) {
        staffUserRepository.findByEmailIgnoreCaseAndDeletedFalse(email).ifPresent(user -> {
            if (oldFirst.equals(user.getFirstName()) && oldLast.equals(user.getLastName())) {
                user.setFirstName(newFirst);
                user.setLastName(newLast);
                staffUserRepository.save(user);
                log.info("Renamed {} from \"{} {}\" to \"{} {}\"", email, oldFirst, oldLast, newFirst, newLast);
            }
        });
    }

    private void seedEmails(String csv, String roleName, String firstName, String lastName) {
        for (String raw : csv.split(",")) {
            String email = raw.trim().toLowerCase();
            if (email.isEmpty() || staffUserRepository.existsByEmailIgnoreCase(email)) {
                continue;
            }
            StaffRole role = staffRoleRepository.findByName(roleName)
                    .orElseThrow(() -> new IllegalStateException(roleName + " role must be seeded before StaffUserSeeder runs"));
            StaffUser user = new StaffUser();
            user.setEmail(email);
            user.setPassword(passwordEncoder.encode(generateTempPassword()));
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setStaffRole(role);
            user.setMustChangePassword(true);
            staffUserRepository.save(user);
            log.info("Seeded {} {} (password is set through the forgot-password flow)", roleName, email);
        }
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
