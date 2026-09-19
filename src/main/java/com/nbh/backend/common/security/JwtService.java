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

    public String generateAccessToken(UserDetails userDetails) {
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
        }

        Instant now = Instant.now();
        return Jwts.builder()
                .claims(claims)
                .subject(userDetails.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenExpirationMs)))
                .signWith(getSigningKey())
                .compact();
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
