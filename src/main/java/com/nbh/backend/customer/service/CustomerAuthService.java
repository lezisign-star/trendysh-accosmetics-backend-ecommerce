package com.nbh.backend.customer.service;

import com.nbh.backend.common.event.CustomerEmailRequestedEvent;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.TooManyRequestsException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.common.util.PhoneNumbers;
import com.nbh.backend.customer.dto.CustomerDtos.AuthResponse;
import com.nbh.backend.customer.dto.CustomerDtos.SessionResult;
import com.nbh.backend.customer.dto.CustomerDtos.ProfileDto;
import com.nbh.backend.customer.dto.CustomerDtos.VerifyResult;
import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.customer.entity.CustomerEmailToken;
import com.nbh.backend.customer.entity.CustomerEmailToken.Purpose;
import com.nbh.backend.customer.repository.CustomerAccountRepository;
import com.nbh.backend.customer.repository.CustomerEmailTokenRepository;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.staff.service.StaffPasswordResetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
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
import java.util.UUID;

/**
 * Shopper accounts: register, sign in, verify the email address, reset a
 * forgotten password, keep a profile.
 *
 * <p>Two rules worth knowing before changing anything here:
 * <ul>
 *   <li><b>Earlier guest orders join an account only after its email is
 *   verified</b> (by the emailed link, or by completing a password reset — both
 *   prove control of the mailbox). Linking on registration alone would let anyone
 *   sign up with a stranger's email address and read that person's past orders.</li>
 *   <li>Sign-in failures never reveal whether an email has an account (same
 *   message, and a dummy password check when the account doesn't exist so timing
 *   doesn't give it away either).</li>
 * </ul>
 */
@Service
public class CustomerAuthService {

    private static final Duration VERIFY_TTL = Duration.ofHours(48);
    private static final Duration RESET_TTL = Duration.ofHours(1);
    /** A second email of the same kind inside this window is refused/skipped, so the endpoint can't be used to spam a mailbox. */
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(2);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CustomerAccountRepository accounts;
    private final CustomerEmailTokenRepository tokens;
    private final CustomerOrderRepository orders;
    private final StaffUserRepository staffUsers;
    private final StaffPasswordResetService staffReset;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginThrottle throttle;
    private final CustomerRefreshTokenService refreshTokens;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String frontendUrl;

    /** Hash checked when the email is unknown, purely to keep response time similar. */
    private final String dummyHash;

    public CustomerAuthService(CustomerAccountRepository accounts,
                                CustomerEmailTokenRepository tokens,
                                CustomerOrderRepository orders,
                                StaffUserRepository staffUsers,
                                StaffPasswordResetService staffReset,
                                PasswordEncoder passwordEncoder,
                                JwtService jwtService,
                                LoginThrottle throttle,
                                CustomerRefreshTokenService refreshTokens,
                                ApplicationEventPublisher events,
                                Clock clock,
                                @Value("${app.frontend-url:http://localhost:5250}") String frontendUrl) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.orders = orders;
        this.staffUsers = staffUsers;
        this.staffReset = staffReset;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.refreshTokens = refreshTokens;
        this.events = events;
        this.clock = clock;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public SessionResult register(String email, String password, String fullName, String phone) {
        String normalized = normalizeEmail(email);
        // Staff and shoppers share one sign-in page (staff are checked first), so a shopper account
        // on a staff email could never be signed into. Same message as a duplicate: no staff-email probing.
        if (accounts.existsByEmailIgnoreCase(normalized) || staffUsers.existsByEmailIgnoreCase(normalized)) {
            throw new ConflictException("An account with that email already exists — try signing in instead");
        }
        CustomerAccount account = new CustomerAccount();
        account.setEmail(normalized);
        account.setPassword(passwordEncoder.encode(password));
        account.setFullName(fullName.trim());
        account.setPhone(blankToNull(PhoneNumbers.normalize(phone)));
        account.setLastLoginAt(Instant.now(clock));
        account = accounts.save(account);

        sendVerificationEmail(account);
        return newSession(account);
    }

    @Transactional
    public SessionResult login(String email, String password) {
        String normalized = normalizeEmail(email);
        throttle.checkAllowed(normalized);

        CustomerAccount account = accounts.findByEmailIgnoreCase(normalized).orElse(null);
        if (account == null) {
            passwordEncoder.matches(password, dummyHash);
            throttle.recordFailure(normalized);
            throw new BadCredentialsException("Invalid email or password");
        }
        if (!account.isEnabled() || !passwordEncoder.matches(password, account.getPassword())) {
            throttle.recordFailure(normalized);
            throw new BadCredentialsException("Invalid email or password");
        }

        throttle.reset(normalized);
        account.setLastLoginAt(Instant.now(clock));
        return newSession(accounts.save(account));
    }

    @Transactional(readOnly = true)
    public ProfileDto profile(UUID accountId) {
        return toProfile(load(accountId));
    }

    @Transactional
    public ProfileDto updateProfile(UUID accountId, String fullName, String phone) {
        CustomerAccount account = load(accountId);
        account.setFullName(fullName.trim());
        account.setPhone(blankToNull(PhoneNumbers.normalize(phone)));
        return toProfile(accounts.save(account));
    }

    @Transactional
    public SessionResult changePassword(UUID accountId, String currentPassword, String newPassword) {
        CustomerAccount account = load(accountId);
        if (!passwordEncoder.matches(currentPassword, account.getPassword())) {
            throw new BadCredentialsException("Current password is incorrect");
        }
        account.setPassword(passwordEncoder.encode(newPassword));
        accounts.save(account);
        // Every other device signs in again; this one gets a fresh session so the shopper isn't bounced.
        refreshTokens.endAllSessions(accountId);
        return newSession(account);
    }

    /** Trades the refresh cookie for a new access token and the next cookie value. */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public SessionResult refresh(String rawRefreshToken) {
        CustomerRefreshTokenService.Rotated rotated = refreshTokens.rotate(rawRefreshToken);
        return new SessionResult(authResponse(rotated.account()), rotated.refreshToken());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.endSession(rawRefreshToken);
    }

    /** Follows the emailed verification link. Repeating it after success is harmless. */
    @Transactional
    public VerifyResult verifyEmail(String rawToken) {
        CustomerEmailToken token = tokens.findByTokenHash(hash(rawToken))
                .filter(t -> t.getPurpose() == Purpose.VERIFY_EMAIL)
                .orElseThrow(() -> new UnprocessableException("This verification link is invalid or has expired"));
        CustomerAccount account = token.getAccount();

        if (token.getUsedAt() != null) {
            if (account.isEmailVerified()) {
                return new VerifyResult(true, 0);
            }
            throw new UnprocessableException("This verification link is invalid or has expired");
        }
        if (isExpired(token)) {
            throw new UnprocessableException("This verification link is invalid or has expired");
        }
        token.setUsedAt(Instant.now(clock));
        tokens.save(token);
        return new VerifyResult(true, markVerifiedAndLinkOrders(account));
    }

    @Transactional
    public void resendVerification(UUID accountId) {
        CustomerAccount account = load(accountId);
        if (account.isEmailVerified()) {
            throw new ConflictException("Your email is already verified");
        }
        enforceCooldown(account, Purpose.VERIFY_EMAIL);
        sendVerificationEmail(account);
    }

    /** Always succeeds from the caller's side — never reveals whether the email has an account. */
    @Transactional
    public void forgotPassword(String email) {
        // Staff share this page; a staff email gets a staff reset link and the shopper flow stops here.
        if (staffReset.requestReset(email)) {
            return;
        }
        accounts.findByEmailIgnoreCase(normalizeEmail(email))
                .filter(CustomerAccount::isEnabled)
                .filter(account -> !inCooldown(account, Purpose.RESET_PASSWORD))
                .ifPresent(account -> {
                    String raw = issueToken(account, Purpose.RESET_PASSWORD, RESET_TTL);
                    events.publishEvent(new CustomerEmailRequestedEvent(account.getEmail(),
                            "Reset your Trendysh password",
                            "Hi " + firstName(account) + ",\n\nSomeone (hopefully you) asked to reset the password for "
                                    + "your Trendysh account. Use this link within the next hour:\n\n"
                                    + frontendUrl + "/reset-password?token=" + raw
                                    + "\n\nIf you didn't ask for this, you can ignore this email — your password stays the same."));
                });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (staffReset.tryReset(rawToken, newPassword)) {
            return;
        }
        CustomerEmailToken token = tokens.findByTokenHash(hash(rawToken))
                .filter(t -> t.getPurpose() == Purpose.RESET_PASSWORD)
                .filter(t -> t.getUsedAt() == null && !isExpired(t))
                .orElseThrow(() -> new UnprocessableException("This reset link is invalid or has expired — please request a new one"));
        CustomerAccount account = token.getAccount();

        account.setPassword(passwordEncoder.encode(newPassword));
        token.setUsedAt(Instant.now(clock));
        tokens.save(token);
        // Receiving the link proves control of the mailbox, same as the verification link.
        markVerifiedAndLinkOrders(account);
        throttle.reset(account.getEmail());
        refreshTokens.endAllSessions(account.getId());
    }

    // ---- internals

    private int markVerifiedAndLinkOrders(CustomerAccount account) {
        account.setEmailVerified(true);
        accounts.save(account);
        return orders.attachGuestOrders(account, account.getEmail());
    }

    private void sendVerificationEmail(CustomerAccount account) {
        String raw = issueToken(account, Purpose.VERIFY_EMAIL, VERIFY_TTL);
        events.publishEvent(new CustomerEmailRequestedEvent(account.getEmail(),
                "Confirm your email for Trendysh",
                "Hi " + firstName(account) + ",\n\nWelcome to Trendysh! Please confirm your email address so we can "
                        + "add any earlier orders you placed with it to your account:\n\n"
                        + frontendUrl + "/verify-email?token=" + raw
                        + "\n\nThis link works for 48 hours."));
    }

    private String issueToken(CustomerAccount account, Purpose purpose, Duration ttl) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        CustomerEmailToken token = new CustomerEmailToken();
        token.setAccount(account);
        token.setPurpose(purpose);
        token.setTokenHash(hash(raw));
        token.setExpiresAt(Instant.now(clock).plus(ttl));
        tokens.save(token);
        return raw;
    }

    private boolean inCooldown(CustomerAccount account, Purpose purpose) {
        return tokens.findFirstByAccountIdAndPurposeOrderByCreatedAtDesc(account.getId(), purpose)
                .map(t -> t.getCreatedAt() != null && t.getCreatedAt().plus(RESEND_COOLDOWN).isAfter(Instant.now(clock)))
                .orElse(false);
    }

    private void enforceCooldown(CustomerAccount account, Purpose purpose) {
        if (inCooldown(account, purpose)) {
            throw new TooManyRequestsException("We just sent one — please wait a couple of minutes before asking again");
        }
    }

    private boolean isExpired(CustomerEmailToken token) {
        return !token.getExpiresAt().isAfter(Instant.now(clock));
    }

    private CustomerAccount load(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new NotFoundException("No such account"));
    }

    private SessionResult newSession(CustomerAccount account) {
        return new SessionResult(authResponse(account), refreshTokens.startSession(account));
    }

    private AuthResponse authResponse(CustomerAccount account) {
        return new AuthResponse(jwtService.generateAccessToken(account), toProfile(account));
    }

    private static ProfileDto toProfile(CustomerAccount a) {
        return new ProfileDto(a.getId(), a.getEmail(), a.getFullName(), a.getPhone(), a.isEmailVerified());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstName(CustomerAccount account) {
        String name = account.getFullName().trim();
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    public static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
