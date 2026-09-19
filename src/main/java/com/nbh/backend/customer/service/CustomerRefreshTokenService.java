package com.nbh.backend.customer.service;

import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.customer.entity.CustomerRefreshToken;
import com.nbh.backend.customer.repository.CustomerRefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Rotating refresh tokens for shopper sign-in. Each refresh marks the token used as rotated and issues
 * the next one in the same family. Presenting an already-rotated token later on means it leaked (or was
 * replayed), so the whole family is revoked and that sign-in ends everywhere.
 *
 * A short grace window covers two tabs refreshing at the same moment: the loser presents a token
 * the winner just rotated, which is a race, not theft, so it is simply given a fresh token.
 */
@Slf4j
@Service
public class CustomerRefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration REUSE_GRACE = Duration.ofSeconds(10);

    /** The account a refresh resolved to plus the replacement cookie value. */
    public record Rotated(CustomerAccount account, String refreshToken) {}

    private final CustomerRefreshTokenRepository tokens;
    private final Clock clock;
    private final Duration ttl;

    public CustomerRefreshTokenService(CustomerRefreshTokenRepository tokens, Clock clock,
                                       @Value("${app.auth.refresh-token-days:30}") long ttlDays) {
        this.tokens = tokens;
        this.clock = clock;
        this.ttl = Duration.ofDays(ttlDays);
    }

    public Duration ttl() {
        return ttl;
    }

    /** Starts a new family — used on register and login. Returns the raw cookie value. */
    @Transactional
    public String startSession(CustomerAccount account) {
        return issue(account, UUID.randomUUID());
    }

    // noRollbackFor: the family revocation on reuse must commit even though we then throw.
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public Rotated rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadCredentialsException("Not signed in");
        }
        Instant now = Instant.now(clock);
        CustomerRefreshToken current = tokens.findByTokenHash(CustomerAuthService.hash(rawToken))
                .orElseThrow(() -> new BadCredentialsException("Not signed in"));

        if (!current.getExpiresAt().isAfter(now)) {
            throw new BadCredentialsException("Your session has expired — please sign in again");
        }
        if (current.getRevokedAt() != null) {
            throw new BadCredentialsException("Your session has expired — please sign in again");
        }
        if (current.getRotatedAt() != null) {
            if (current.getRotatedAt().plus(REUSE_GRACE).isBefore(now)) {
                tokens.revokeFamily(current.getFamilyId(), now);
                log.warn("Refresh token reuse detected for customer {} — family revoked", current.getAccount().getId());
                throw new BadCredentialsException("Your session has expired — please sign in again");
            }
        } else {
            current.setRotatedAt(now);
            tokens.save(current);
        }
        CustomerAccount account = current.getAccount();
        if (!account.isEnabled()) {
            tokens.revokeFamily(current.getFamilyId(), now);
            throw new BadCredentialsException("Not signed in");
        }
        return new Rotated(account, issue(account, current.getFamilyId()));
    }

    /** Sign out: ends this sign-in (its whole family). Unknown or empty values are ignored. */
    @Transactional
    public void endSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        tokens.findByTokenHash(CustomerAuthService.hash(rawToken))
                .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), Instant.now(clock)));
    }

    /** Password changed or reset: every device signs in again. */
    @Transactional
    public void endAllSessions(UUID accountId) {
        tokens.revokeAllForAccount(accountId, Instant.now(clock));
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpired() {
        int removed = tokens.deleteExpiredBefore(Instant.now(clock).minus(Duration.ofDays(7)));
        if (removed > 0) log.info("Purged {} expired customer refresh tokens", removed);
    }

    private String issue(CustomerAccount account, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        CustomerRefreshToken token = new CustomerRefreshToken();
        token.setAccount(account);
        token.setFamilyId(familyId);
        token.setTokenHash(CustomerAuthService.hash(raw));
        token.setExpiresAt(Instant.now(clock).plus(ttl));
        tokens.save(token);
        return raw;
    }
}
