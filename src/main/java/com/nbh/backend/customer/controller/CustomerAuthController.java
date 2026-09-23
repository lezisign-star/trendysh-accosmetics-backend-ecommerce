package com.nbh.backend.customer.controller;

import com.nbh.backend.customer.dto.CustomerDtos.AuthResponse;
import com.nbh.backend.customer.dto.CustomerDtos.ChangePasswordRequest;
import com.nbh.backend.customer.dto.CustomerDtos.ForgotPasswordRequest;
import com.nbh.backend.customer.dto.CustomerDtos.LoginRequest;
import com.nbh.backend.customer.dto.CustomerDtos.ProfileDto;
import com.nbh.backend.customer.dto.CustomerDtos.RegisterRequest;
import com.nbh.backend.customer.dto.CustomerDtos.SessionResult;
import com.nbh.backend.customer.dto.CustomerDtos.ResetPasswordRequest;
import com.nbh.backend.customer.dto.CustomerDtos.TokenRequest;
import com.nbh.backend.customer.dto.CustomerDtos.UpdateProfileRequest;
import com.nbh.backend.customer.dto.CustomerDtos.VerifyResult;
import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.customer.service.CustomerAuthService;
import com.nbh.backend.customer.service.CustomerRefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Shopper accounts. register / login / verify-email / forgot-password /
 * reset-password are public (see SecurityConfig); everything else here needs a
 * customer token. Staff tokens and customer tokens are not interchangeable.
 */
@RestController
@RequestMapping("/api/v1/customer/auth")
public class CustomerAuthController {

    private final CustomerAuthService authService;
    private final CustomerRefreshTokenService refreshTokens;
    private final String cookieName;
    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final List<String> allowedOrigins;

    public CustomerAuthController(CustomerAuthService authService,
                                   CustomerRefreshTokenService refreshTokens,
                                   @Value("${app.auth.refresh-cookie-name:trendysh_rt}") String cookieName,
                                   @Value("${app.auth.cookie-secure:true}") boolean cookieSecure,
                                   // None lets a storefront on another site (Render) send the cookie to this API;
                                   // use Lax/Strict once both live under one registrable domain.
                                   @Value("${app.auth.cookie-same-site:None}") String cookieSameSite,
                                   @Value("${app.cors.allowed-origins:http://localhost:5173}") String allowedOriginsRaw) {
        this.authService = authService;
        this.refreshTokens = refreshTokens;
        this.cookieName = cookieName;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
        this.allowedOrigins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(o -> o.trim().replaceAll("/+$", "")).filter(o -> !o.isEmpty()).toList();
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        return withCookie(authService.register(request.email(), request.password(), request.fullName(), request.phone()), response);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return withCookie(authService.login(request.email(), request.password()), response);
    }

    /** Public: authenticated by the httpOnly refresh cookie, not a bearer token. */
    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        requireTrustedOrigin(request);
        try {
            return withCookie(authService.refresh(readCookie(request)), response);
        } catch (org.springframework.security.authentication.BadCredentialsException e) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
            throw e;
        }
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        requireTrustedOrigin(request);
        authService.logout(readCookie(request));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    @PostMapping("/verify-email")
    public VerifyResult verifyEmail(@Valid @RequestBody TokenRequest request) {
        return authService.verifyEmail(request.token());
    }

    @PostMapping("/forgot-password")
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
    }

    @PostMapping("/reset-password")
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.email(), request.code(), request.newPassword());
    }

    @PostMapping("/resend-verification")
    public void resendVerification(@AuthenticationPrincipal CustomerAccount customer) {
        authService.resendVerification(customer.getId());
    }

    @GetMapping("/me")
    public ProfileDto me(@AuthenticationPrincipal CustomerAccount customer) {
        return authService.profile(customer.getId());
    }

    @PatchMapping("/me")
    public ProfileDto updateProfile(@AuthenticationPrincipal CustomerAccount customer,
                                     @Valid @RequestBody UpdateProfileRequest request) {
        return authService.updateProfile(customer.getId(), request.fullName(), request.phone());
    }

    @PostMapping("/change-password")
    public AuthResponse changePassword(@AuthenticationPrincipal CustomerAccount customer,
                                        @Valid @RequestBody ChangePasswordRequest request,
                                        HttpServletResponse response) {
        return withCookie(authService.changePassword(customer.getId(), request.currentPassword(), request.newPassword()), response);
    }

    private AuthResponse withCookie(SessionResult session, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), refreshTokens.ttl()).toString());
        return session.auth();
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                // Scoped so the cookie is only ever sent to the auth endpoints, not every API call.
                .path("/api/v1/customer/auth")
                .maxAge(maxAge)
                .build();
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (cookieName.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    /**
     * The cookie rides along on cross-site requests (SameSite=None), so refresh and logout only
     * accept browsers that say they came from one of our own storefront origins. Non-browser
     * callers send no Origin header and cannot ride a victim's cookie anyway.
     */
    private void requireTrustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin.replaceAll("/+$", ""))) {
            throw new AccessDeniedException("Origin not allowed");
        }
    }
}
