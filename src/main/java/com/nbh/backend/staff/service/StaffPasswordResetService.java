package com.nbh.backend.staff.service;

import com.nbh.backend.common.email.EmailTemplates;
import com.nbh.backend.common.event.CustomerEmailRequestedEvent;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.staff.entity.StaffPasswordResetToken;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffPasswordResetTokenRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Password reset for staff, sharing the storefront's forgot/reset-password pages with shoppers.
 * The shopper flow asks this service first: if the email is a staff account it handles the request,
 * otherwise the shopper flow carries on. Like the shopper flow it never reveals whether an email
 * has an account. A reset is a 6-digit code, emailed, valid for 10 minutes, locked out after
 * {@value #MAX_ATTEMPTS} wrong guesses (a fresh request issues a new code and resets the count).
 */
@Service
public class StaffPasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 5;
    private static final String GENERIC_INVALID = "That code is invalid or has expired — please request a new one";
    /** Same floor as the staff change-password endpoint. */
    private static final int MIN_PASSWORD_LENGTH = 10;

    private final StaffUserRepository staff;
    private final StaffPasswordResetTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String frontendUrl;

    public StaffPasswordResetService(StaffUserRepository staff,
                                     StaffPasswordResetTokenRepository tokens,
                                     PasswordEncoder passwordEncoder,
                                     ApplicationEventPublisher events,
                                     Clock clock,
                                     @Value("${app.frontend-url:http://localhost:5250}") String frontendUrl) {
        this.staff = staff;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.clock = clock;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /**
     * @return true if the email belongs to a staff account (whether or not a code was sent, e.g.
     *         when it is inside the resend cooldown), so the caller knows not to treat it as a shopper.
     */
    @Transactional
    public boolean requestReset(String email) {
        StaffUser user = staff.findByEmailIgnoreCaseAndDeletedFalse(email.trim()).orElse(null);
        if (user == null) {
            return false;
        }
        if (!user.isEnabled() || inCooldown(user)) {
            return true;
        }
        String code = issueOtp(user);
        String intro = "Someone (hopefully you) asked to reset the password for your Trendysh staff account. "
                + "Your verification code is below.";
        events.publishEvent(new CustomerEmailRequestedEvent(user.getEmail(),
                "Your Trendysh admin password reset code",
                "Hi " + user.getFirstName() + ",\n\n" + intro + "\n\n" + code
                        + "\n\nIt expires in 10 minutes. If you didn't ask for this, you can ignore this email —"
                        + " your password stays the same.",
                EmailTemplates.otpCode(frontendUrl + "/trendysh-logo.png", user.getFirstName(), intro, code, 10)));
        return true;
    }

    /**
     * Sends a brand-new staff account its first sign-in code, reusing the exact same OTP
     * mechanism as a password reset (same token table, same 10-minute/5-attempt lockout) —
     * accepting the invite IS a password reset, just for an account nobody has signed into yet.
     * Called by {@code StaffManagementService} right after it saves a newly-invited user.
     */
    @Transactional
    public void issueInvite(StaffUser user) {
        String code = issueOtp(user);
        String intro = "You've been added as staff on the Trendysh admin dashboard. Use the code below to"
                + " set your password and sign in.";
        events.publishEvent(new CustomerEmailRequestedEvent(user.getEmail(),
                "You're invited to the Trendysh admin dashboard",
                "Hi " + user.getFirstName() + ",\n\n" + intro + "\n\n" + code
                        + "\n\nIt expires in 10 minutes. Go to " + frontendUrl
                        + "/login and click \"Forgot your password?\" to enter it and set your password.",
                EmailTemplates.otpCode(frontendUrl + "/trendysh-logo.png", user.getFirstName(), intro, code, 10)));
    }

    /**
     * @return true if the email is a staff account (the code/password were then checked, throwing on
     *         a problem); false if the email is not a staff account at all (the caller then tries the
     *         shopper flow).
     */
    // noRollbackFor: a wrong-code guess must still commit its attempts increment even though we then throw.
    @Transactional(noRollbackFor = UnprocessableException.class)
    public boolean tryReset(String email, String code, String newPassword) {
        StaffUser user = staff.findByEmailIgnoreCaseAndDeletedFalse(email.trim()).orElse(null);
        if (user == null) {
            return false;
        }
        // Locked: see the repository method — this is where guesses are counted.
        StaffPasswordResetToken token = tokens.findTopByStaffIdOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new UnprocessableException(GENERIC_INVALID));
        verifyCode(token, code);

        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new UnprocessableException("Staff passwords need at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setTempPasswordExpiresAt(null);
        staff.save(user);
        token.setUsedAt(Instant.now(clock));
        tokens.save(token);
        return true;
    }

    private void verifyCode(StaffPasswordResetToken token, String code) {
        Instant now = Instant.now(clock);
        if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw new UnprocessableException(GENERIC_INVALID);
        }
        if (token.getAttempts() >= MAX_ATTEMPTS) {
            token.setUsedAt(now);
            tokens.save(token);
            throw new UnprocessableException("Too many incorrect attempts — please request a new code");
        }
        if (!hash(code.trim()).equals(token.getTokenHash())) {
            token.setAttempts(token.getAttempts() + 1);
            tokens.save(token);
            int left = MAX_ATTEMPTS - token.getAttempts();
            throw new UnprocessableException(
                    left > 0 ? "Incorrect code — " + left + " attempt" + (left == 1 ? "" : "s") + " left"
                              : "Too many incorrect attempts — please request a new code");
        }
    }

    private String issueOtp(StaffUser user) {
        String code = generateOtp();

        StaffPasswordResetToken token = new StaffPasswordResetToken();
        token.setStaff(user);
        token.setTokenHash(hash(code));
        token.setExpiresAt(Instant.now(clock).plus(OTP_TTL));
        tokens.save(token);
        return code;
    }

    private static String generateOtp() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private boolean inCooldown(StaffUser user) {
        return tokens.findFirstByStaffIdOrderByCreatedAtDesc(user.getId())
                .map(t -> t.getCreatedAt() != null && t.getCreatedAt().plus(RESEND_COOLDOWN).isAfter(Instant.now(clock)))
                .orElse(false);
    }

    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
