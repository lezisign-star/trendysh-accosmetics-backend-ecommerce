package com.nbh.backend.auth;

import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.customer.repository.CustomerAccountRepository;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Found in the 2026-10-06 go-live QA: twelve wrong passwords in a row for a staff account were each simply refused,
 * and the thirteenth, correct one signed in, so a staff password (the owner's, whose address is on the public site)
 * could be guessed without limit. Staff now get the same slow-down shoppers have: five wrong passwords for one email
 * close the door for a while.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffLoginThrottleIntegrationTest {

    private static final String PASSWORD = "a-long-enough-staff-password";

    @Autowired private MockMvc mvc;
    @Autowired private CustomerAccountRepository customers;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private StaffRoleRepository staffRoles;
    @Autowired private PasswordEncoder passwordEncoder;

    private StaffUser newStaff() {
        StaffUser user = new StaffUser();
        user.setEmail("throttle-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setFirstName("Throttle");
        user.setLastName("Test");
        user.setStaffRole(staffRoles.findByName("CASHIER").orElseThrow());
        return staffUsers.save(user);
    }

    private ResultActions signIn(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void fiveWrongPasswordsLockTheAccountOutEvenForTheRightPassword() throws Exception {
        String email = newStaff().getEmail();
        for (int i = 0; i < 5; i++) {
            signIn(email, "wrong-password-" + i).andExpect(status().isUnauthorized());
        }

        signIn(email, PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Too many failed sign-in attempts")));
        // The email is matched ignoring case, like the shopper sign-in.
        signIn(email.toUpperCase(), PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void aSuccessfulSignInClearsTheCountSoOccasionalTyposNeverAddUp() throws Exception {
        String email = newStaff().getEmail();
        for (int i = 0; i < 4; i++) signIn(email, "typo-" + i).andExpect(status().isUnauthorized());
        signIn(email, PASSWORD).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) signIn(email, "typo-again-" + i).andExpect(status().isUnauthorized());

        signIn(email, PASSWORD).andExpect(status().isOk());
    }

    /**
     * Found in the 2026-10-06 live QA, and caused by the lockout above: the one sign-in form tries the staff login for
     * EVERY email before it tries the shopper login, so each shopper visit added a failure to a counter that locks at five.
     * A shopper who signed in five times in a quarter of an hour, or anyone guessing at their email through the staff door,
     * shut them out. Only an email that belongs to a staff account counts now.
     */
    @Test
    void aShoppersSignInsAreNeverSlowedByTheStaffStepThatComesFirst() throws Exception {
        CustomerAccount shopper = new CustomerAccount();
        shopper.setEmail("shopper-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        shopper.setPassword(passwordEncoder.encode(PASSWORD));
        shopper.setFullName("Njeri Shopper");
        customers.save(shopper);

        for (int visit = 0; visit < 8; visit++) {
            // Not a staff login: a plain 401 every time, never 429, and then the shopper step lets them in.
            signIn(shopper.getEmail(), PASSWORD).andExpect(status().isUnauthorized());
            shopperSignIn(shopper.getEmail(), PASSWORD).andExpect(status().isOk());
        }
        // Guessing at the shopper's email through the staff door cannot lock them out either.
        for (int i = 0; i < 8; i++) signIn(shopper.getEmail(), "wrong-" + i).andExpect(status().isUnauthorized());
        shopperSignIn(shopper.getEmail(), PASSWORD).andExpect(status().isOk());
    }

    private ResultActions shopperSignIn(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/customer/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void oneAccountsLockoutDoesNotAffectAnotherAndGuessingMadeUpEmailsIsHarmless() throws Exception {
        String locked = newStaff().getEmail();
        String other = newStaff().getEmail();
        for (int i = 0; i < 6; i++) signIn(locked, "wrong-" + i);

        signIn(other, PASSWORD).andExpect(status().isOk());
        for (int i = 0; i < 6; i++) signIn("nobody-" + UUID.randomUUID() + "@example.com", "wrong").andExpect(status().is4xxClientError());
    }
}
