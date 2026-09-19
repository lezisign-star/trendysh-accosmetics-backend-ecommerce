package com.nbh.backend.customer.controller;

import com.nbh.backend.customer.dto.CustomerDtos.AuthResponse;
import com.nbh.backend.customer.dto.CustomerDtos.ChangePasswordRequest;
import com.nbh.backend.customer.dto.CustomerDtos.ForgotPasswordRequest;
import com.nbh.backend.customer.dto.CustomerDtos.LoginRequest;
import com.nbh.backend.customer.dto.CustomerDtos.ProfileDto;
import com.nbh.backend.customer.dto.CustomerDtos.RegisterRequest;
import com.nbh.backend.customer.dto.CustomerDtos.ResetPasswordRequest;
import com.nbh.backend.customer.dto.CustomerDtos.TokenRequest;
import com.nbh.backend.customer.dto.CustomerDtos.UpdateProfileRequest;
import com.nbh.backend.customer.dto.CustomerDtos.VerifyResult;
import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.customer.service.CustomerAuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shopper accounts. register / login / verify-email / forgot-password /
 * reset-password are public (see SecurityConfig); everything else here needs a
 * customer token. Staff tokens and customer tokens are not interchangeable.
 */
@RestController
@RequestMapping("/api/v1/customer/auth")
public class CustomerAuthController {

    private final CustomerAuthService authService;

    public CustomerAuthController(CustomerAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request.email(), request.password(), request.fullName(), request.phone());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
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
        authService.resetPassword(request.token(), request.newPassword());
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
    public void changePassword(@AuthenticationPrincipal CustomerAccount customer,
                                @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(customer.getId(), request.currentPassword(), request.newPassword());
    }
}
