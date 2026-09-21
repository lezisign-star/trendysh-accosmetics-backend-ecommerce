package com.nbh.backend.staff.service;

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
import java.util.Base64;
import java.util.HexFormat;

/**
 * Password reset for staff, sharing the storefront's forgot/reset-password pages with shoppers.
 * The shopper flow asks this service first: if the email is a staff account it handles the request,
 * otherwise the shopper flow carries on. Like the shopper flow it never reveals whether an email
 * has an account, and the link works once, for an hour.
 */
@Service
public class StaffPasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration LINK_TTL = Duration.ofHours(1);
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(2);
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
     * @return true if the email belongs to a staff account (whether or not a link was sent, e.g.
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
        String raw = issueToken(user);
        events.publishEvent(new CustomerEmailRequestedEvent(user.getEmail(),
                "Reset your Trendysh admin password",
                "Hi " + user.getFirstName() + ",\n\nSomeone (hopefully you) asked to reset the password for your "
                        + "Trendysh staff account. Use this link within the next hour:\n\n"
                        + frontendUrl + "/reset-password?token=" + raw
                        + "\n\nIf you didn't ask for this, you can ignore this email — your password stays the same."));
        return true;
    }

    /**
     * @return true if the token was a staff reset link and the password was changed; false if the
     *         token is not a staff link at all (the caller then tries the shopper flow).
     */
    @Transactional
    public boolean tryReset(String rawToken, String newPassword) {
        StaffPasswordResetToken token = tokens.findByTokenHash(hash(rawToken)).orElse(null);
        if (token == null) {
            return false;
        }
        Instant now = Instant.now(clock);
        if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw new UnprocessableException("This reset link is invalid or has expired — please request a new one");
        }
        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new UnprocessableException("Staff passwords need at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        StaffUser user = token.getStaff();
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setTempPasswordExpiresAt(null);
        staff.save(user);
        token.setUsedAt(now);
        tokens.save(token);
        return true;
    }

    private String issueToken(StaffUser user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        StaffPasswordResetToken token = new StaffPasswordResetToken();
        token.setStaff(user);
        token.setTokenHash(hash(raw));
        token.setExpiresAt(Instant.now(clock).plus(LINK_TTL));
        tokens.save(token);
        return raw;
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
