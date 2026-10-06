package com.nbh.backend.auth.controller;

import com.nbh.backend.auth.dto.AuthResponse;
import com.nbh.backend.auth.dto.ChangePasswordRequest;
import com.nbh.backend.auth.dto.LoginRequest;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.customer.service.LoginThrottle;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffUserRepository;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Staff/admin login only — there is no customer-facing auth yet (that's Phase 2,
 * on the e-commerce backend). Deliberately no refresh-token endpoint in this
 * first pass; access tokens are relatively long-lived (see
 * app.jwt.access-token-expiration-ms) until that's built out.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final StaffUserRepository staffUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle loginThrottle;

    public AuthController(AuthenticationManager authenticationManager,
                           JwtService jwtService,
                           StaffUserRepository staffUserRepository,
                           PasswordEncoder passwordEncoder,
                           LoginThrottle loginThrottle) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.staffUserRepository = staffUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginThrottle = loginThrottle;
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
        return toAuthResponse(staff);
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

        return toAuthResponse(staff);
    }

    @GetMapping("/me")
    public AuthResponse me(@AuthenticationPrincipal StaffUser principal) {
        return toAuthResponse(principal);
    }

    private AuthResponse toAuthResponse(StaffUser staff) {
        Set<String> permissions = staff.getResolvedPermissions().stream()
                .map(Enum::name)
                .collect(Collectors.toSet());

        return new AuthResponse(
                jwtService.generateAccessToken(staff),
                staff.getId(),
                staff.getEmail(),
                staff.getFullName(),
                staff.getStaffRole() != null ? staff.getStaffRole().getName() : null,
                permissions,
                staff.isMustChangePassword()
        );
    }
}
