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

    @Value("${app.bootstrap.admin-email:admin@nairobibeautyhub.co.ke}")
    private String bootstrapEmail;

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
        if (staffUserRepository.existsByEmailIgnoreCase(bootstrapEmail)) {
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

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
