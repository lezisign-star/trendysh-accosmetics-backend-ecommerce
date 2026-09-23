package com.nbh.backend.staff.dto;

import com.nbh.backend.staff.entity.StaffRole;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request/response shapes for the admin staff-management screen. */
public final class StaffDtos {

    private StaffDtos() {}

    public record InviteRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank String roleName) {}

    public record RoleUpdateRequest(@NotBlank String roleName) {}

    public record StatusUpdateRequest(boolean enabled) {}

    public record RoleDto(UUID id, String name, String displayName) {
        public static RoleDto from(StaffRole role) {
            return new RoleDto(role.getId(), role.getName(), role.getDisplayName());
        }
    }

    /**
     * status is derived, not stored: PENDING means the account has never completed a password
     * reset (still on the unusable random password it was created with — true for both a fresh
     * invite and the legacy env-var-seeded accounts until they first sign in).
     */
    public record StaffDto(
            UUID id, String email, String firstName, String lastName, String fullName,
            String roleName, String roleDisplayName, String status, Instant createdAt) {

        public static StaffDto from(StaffUser user) {
            String status = !user.isEnabled() ? "DISABLED" : user.isMustChangePassword() ? "PENDING" : "ACTIVE";
            return new StaffDto(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                    user.getFullName(), user.getStaffRole().getName(), user.getStaffRole().getDisplayName(),
                    status, user.getCreatedAt());
        }
    }
}
