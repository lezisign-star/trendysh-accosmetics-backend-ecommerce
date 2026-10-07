package com.nbh.backend.auth.controller;

import com.nbh.backend.auth.dto.AuthResponse;
import com.nbh.backend.auth.dto.ChangePasswordRequest;
import com.nbh.backend.auth.dto.LoginRequest;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.customer.service.LoginThrottle;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffUserRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Staff/admin sign-in (shoppers have their own, see CustomerAuthController). A staff token is the only credential:
 * there is no stored refresh token, which would be a long-lived secret on shared shop computers. Instead a token
 * lasts app.jwt.access-token-expiration-ms (8 hours) and {@link #renew} swaps a still-valid one for a fresh one while
 * the person keeps working, up to app.jwt.staff-session-max-days since they really signed in. An idle sign-in just
 * runs out.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final StaffUserRepository staffUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle loginThrottle;
    private final Duration staffSessionMax;

    public AuthController(AuthenticationManager authenticationManager,
                           JwtService jwtService,
                           StaffUserRepository staffUserRepository,
                           PasswordEncoder passwordEncoder,
                           LoginThrottle loginThrottle,
                           @Value("${app.jwt.staff-session-max-days:7}") long staffSessionMaxDays) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.staffUserRepository = staffUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginThrottle = loginThrottle;
        this.staffSessionMax = Duration.ofDays(staffSessionMaxDays);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        // Same slow-down the shop's customers get: five wrong passwords for one email close the door for a while
        // (a staff reset code, which has its own limit, always works). Before this a staff password could be
        // guessed without limit.
        String throttleKey = LoginThrottle.staffKey(request.email());
        loginThrottle.checkAllowed(throttleKey);
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (org.springframework.security.core.AuthenticationException e) {
            // Only a real staff account's email counts. The one sign-in form tries THIS login first for every email and
            // only then the shopper login, so counting a shopper's email here (found in the 2026-10-06 live QA) shut
            // shoppers out of signing in after five visits in a quarter of an hour, and let anyone lock a shopper out
            // by guessing here. An email that is not staff never adds up, so it can never be locked.
            if (staffUserRepository.existsByEmailIgnoreCase(request.email())) {
                loginThrottle.recordFailure(throttleKey);
            }
            throw new BadCredentialsException("Invalid email or password");
        }

        loginThrottle.reset(throttleKey);
        StaffUser staff = (StaffUser) authentication.getPrincipal();
        return toAuthResponse(staff, jwtService.generateAccessToken(staff, Instant.now()));
    }

    @PostMapping("/change-password")
    public AuthResponse changePassword(@AuthenticationPrincipal StaffUser principal,
                                        @Valid @RequestBody ChangePasswordRequest request) {
        StaffUser staff = staffUserRepository.findById(principal.getId()).orElseThrow();

        if (!passwordEncoder.matches(request.currentPassword(), staff.getPassword())) {
            throw new BadCredentialsException("Current password is incorrect");
        }

        staff.setPassword(passwordEncoder.encode(request.newPassword()));
        staff.setMustChangePassword(false);
        staff.setTempPasswordExpiresAt(null);
        staffUserRepository.save(staff);

        // A new password is a new sign-in: the old tokens stop being renewable (see renew).
        return toAuthResponse(staff, jwtService.generateAccessToken(staff, Instant.now()));
    }

    /**
     * "Am I still signed in, and who am I?" It answers with the token it was called with. It used to mint a fresh
     * 8-hour token on every call, which made any limit on renewing meaningless: anyone holding a token could keep it
     * alive for ever by asking who they were. Renewing is {@link #renew}'s job, and it has limits.
     */
    @GetMapping("/me")
    public AuthResponse me(@AuthenticationPrincipal StaffUser principal,
                            @RequestHeader("Authorization") String authorization) {
        return toAuthResponse(principal, bearerToken(authorization));
    }

    /**
     * Swaps a still-valid staff token for a fresh one, so a person who keeps using the dashboard and the shop is not
     * sent back to the sign-in form in the middle of the day. Limits, so this is not an endless extension:
     * <ul>
     *   <li>the token must still be valid (an expired one, a disabled or deleted account, a shopper's token: the
     *   usual refusal, answered 403 like every other unauthenticated call);</li>
     *   <li>the sign-in it descends from must be less than {@code staff-session-max-days} old (7 days): the fresh
     *   token carries the ORIGINAL sign-in time, so renewing never moves that limit. Past it, 401;</li>
     *   <li>the password must not have changed since the token was issued (401): a changed or reset password ends
     *   the ability to renew the old tokens, which would otherwise stay alive for days.</li>
     * </ul>
     * The reply carries the CURRENT permissions, so a role change reaches a signed-in person at the next renewal.
     * A 401 here does not end the current token, which keeps working until it runs out.
     */
    @PostMapping("/renew")
    public AuthResponse renew(@AuthenticationPrincipal StaffUser principal,
                               @RequestHeader("Authorization") String authorization) {
        String presented = bearerToken(authorization);
        StaffUser staff = staffUserRepository.findById(principal.getId())
                .orElseThrow(() -> new BadCredentialsException("Please sign in again"));

        Instant signedInAt = jwtService.extractSignedInAt(presented);
        if (signedInAt.plus(staffSessionMax).isBefore(Instant.now())) {
            throw new BadCredentialsException("You have been signed in for a long time — please sign in again");
        }
        String issuedUnder = jwtService.extractPasswordVersion(presented);
        if (issuedUnder != null && !issuedUnder.equals(JwtService.passwordVersion(staff.getPassword()))) {
            throw new BadCredentialsException("Your password changed — please sign in again");
        }
        return toAuthResponse(staff, jwtService.generateAccessToken(staff, signedInAt));
    }

    private static String bearerToken(String authorizationHeader) {
        return authorizationHeader.substring("Bearer ".length());
    }

    private AuthResponse toAuthResponse(StaffUser staff, String accessToken) {
        Set<String> permissions = staff.getResolvedPermissions().stream()
                .map(Enum::name)
                .collect(Collectors.toSet());

        return new AuthResponse(
                accessToken,
                staff.getId(),
                staff.getEmail(),
                staff.getFullName(),
                staff.getStaffRole() != null ? staff.getStaffRole().getName() : null,
                permissions,
                staff.isMustChangePassword()
        );
    }
}
