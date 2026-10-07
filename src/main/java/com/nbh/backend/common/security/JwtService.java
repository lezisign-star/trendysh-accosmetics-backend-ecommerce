package com.nbh.backend.common.security;

import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.staff.entity.StaffUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Adapted from Moments Packaging's JwtService, stripped of customer/refresh/
 * impersonation concerns not yet built for NBH (staff-only auth so far).
 */
@Slf4j
@Service
public class JwtService {

    /** Must match application.yml's dev default exactly — this is the value being guarded against. */
    private static final String DEV_DEFAULT_SECRET =
            "dev-only-insecure-secret-change-me-before-any-real-deployment-32bytes";
    public static final String KIND_STAFF = "STAFF";
    public static final String KIND_CUSTOMER = "CUSTOMER";
    private static final List<String> SAFE_PROFILES = List.of("dev", "test", "default");

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    /** Shoppers stay signed in through the refresh cookie, so their access token can be short-lived. */
    @Value("${app.jwt.customer-access-token-expiration-ms:900000}")
    private long customerAccessTokenExpirationMs;

    private final Environment environment;

    public JwtService(Environment environment) {
        this.environment = environment;
    }

    /**
     * Audit finding F8 (session 3): the dev-only JWT secret in application.yml
     * had no guard at all — if JWT_SECRET was ever unset or mistyped in a real
     * deployment, the app would boot silently and sign every token with a
     * value committed to the repo, letting anyone who has (or guesses) it
     * forge a SUPER_ADMIN token. This fails the boot loudly instead, unless
     * an active profile explicitly says it's fine (dev/test/default — "no
     * profile set" also means local dev).
     */
    @PostConstruct
    void validateSecretIsNotTheDevDefaultOutsideDev() {
        boolean usingDevDefault = DEV_DEFAULT_SECRET.equals(secret);
        if (!usingDevDefault) {
            return;
        }
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean isSafeEnvironment = activeProfiles.isEmpty()
                || activeProfiles.stream().anyMatch(SAFE_PROFILES::contains);
        if (!isSafeEnvironment) {
            throw new IllegalStateException(
                    "JWT_SECRET is unset (or explicitly set to the dev-only placeholder) while active profile(s) "
                            + activeProfiles + " do not include dev/test — refusing to start. Set a real random "
                            + "JWT_SECRET before deploying anywhere real.");
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Epoch seconds of the password sign-in a staff session began with; kept through renewals. */
    public static final String CLAIM_SIGNED_IN_AT = "authTime";
    /** A short fingerprint of the staff password the token was issued under (see {@link #passwordVersion}). */
    public static final String CLAIM_PASSWORD_VERSION = "pwv";

    public String generateAccessToken(UserDetails userDetails) {
        return generateAccessToken(userDetails, Instant.now());
    }

    /**
     * {@code signedInAt} is when the person really signed in with their password. A renewed staff token carries the
     * ORIGINAL moment forward, so a session has an absolute limit however often it is renewed (AuthController#renew).
     */
    public String generateAccessToken(UserDetails userDetails, Instant signedInAt) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        // Which kind of principal this token belongs to. JwtAuthFilter uses it to pick the
        // right account table, so a customer email can never resolve to a staff account.
        // Tokens issued before this claim existed have none and are treated as staff.
        claims.put("kind", userDetails instanceof CustomerAccount ? KIND_CUSTOMER : KIND_STAFF);

        if (userDetails instanceof CustomerAccount customer) {
            claims.put("userId", customer.getId().toString());
        }

        if (userDetails instanceof StaffUser staff) {
            claims.put("userId", staff.getId().toString());
            claims.put("firstName", staff.getFirstName());
            claims.put("lastName", staff.getLastName());
            claims.put("mustChangePassword", staff.isMustChangePassword());
            if (staff.getStaffRole() != null) {
                claims.put("staffRole", staff.getStaffRole().getName());
                claims.put("staffRoleDisplay", staff.getStaffRole().getDisplayName());
            }
            claims.put("permissions", staff.getResolvedPermissions().stream()
                    .map(Enum::name)
                    .collect(Collectors.toList()));
            claims.put(CLAIM_SIGNED_IN_AT, signedInAt.getEpochSecond());
            claims.put(CLAIM_PASSWORD_VERSION, passwordVersion(staff.getPassword()));
        }

        Instant now = Instant.now();
        long ttlMs = userDetails instanceof CustomerAccount ? customerAccessTokenExpirationMs : accessTokenExpirationMs;
        return Jwts.builder()
                .claims(claims)
                .subject(userDetails.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMs)))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * What a staff token remembers of the password it was issued under: the first 16 hex characters of a SHA-256 of the
     * stored password hash. A token's payload is readable by anyone holding it, and this reveals nothing usable; it
     * only lets a renewal notice that the password has changed since (a changed password must end a session that would
     * otherwise be renewed for days).
     */
    public static String passwordVersion(String passwordHash) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** When the sign-in began; for a token issued before that was recorded, when the token itself was issued. */
    public Instant extractSignedInAt(String token) {
        Number signedIn = extractClaim(token, claims -> claims.get(CLAIM_SIGNED_IN_AT, Number.class));
        if (signedIn != null) {
            return Instant.ofEpochSecond(signedIn.longValue());
        }
        Date issued = extractClaim(token, Claims::getIssuedAt);
        return issued != null ? issued.toInstant() : Instant.now();
    }

    /** The password fingerprint the token was issued under, or null for a token issued before it was recorded. */
    public String extractPasswordVersion(String token) {
        return extractClaim(token, claims -> claims.get(CLAIM_PASSWORD_VERSION, String.class));
    }

    /** STAFF for tokens with no claim (issued before customer accounts existed). */
    public String extractKind(String token) {
        String kind = extractClaim(token, claims -> claims.get("kind", String.class));
        return kind == null ? KIND_STAFF : kind;
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claimsResolver.apply(claims);
    }
}
