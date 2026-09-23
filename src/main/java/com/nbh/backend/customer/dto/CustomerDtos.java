package com.nbh.backend.customer.dto;

import com.nbh.backend.order.entity.OrderStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Request/response shapes for customer accounts. Passwords are capped at 72
 * characters because BCrypt only ever reads the first 72 bytes — accepting more
 * would silently ignore the tail (and newer Spring versions reject it outright).
 */
public final class CustomerDtos {

    private CustomerDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72, message = "Password must be 8–72 characters") String password,
            @NotBlank @Size(max = 200) String fullName,
            @Size(max = 20) String phone) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record TokenRequest(@NotBlank String token) {}

    public record ForgotPasswordRequest(@NotBlank @Email String email) {}

    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "Enter the 6-digit code") String code,
            @NotBlank @Size(min = 8, max = 72, message = "Password must be 8–72 characters") String newPassword) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 72, message = "Password must be 8–72 characters") String newPassword) {}

    public record UpdateProfileRequest(
            @NotBlank @Size(max = 200) String fullName,
            @Size(max = 20) String phone) {}

    public record ProfileDto(UUID id, String email, String fullName, String phone, boolean emailVerified) {}

    public record AuthResponse(String accessToken, ProfileDto profile) {}

    /** What the service hands the controller: the JSON body plus the refresh value that goes in the httpOnly cookie, never in the body. */
    public record SessionResult(AuthResponse auth, String refreshToken) {}

    /** Result of following an emailed link: whether the address is now verified and how many earlier guest orders joined the account. */
    public record VerifyResult(boolean emailVerified, int ordersLinked) {}

    /**
     * {@code status} is the real enum (not a string) so this can be built by a JPQL
     * constructor expression — Jackson still serialises it as plain text.
     */
    public record MyOrderDto(String orderNo, OrderStatus status, long itemCount, BigDecimal grandTotal, Instant createdAt) {}
}
