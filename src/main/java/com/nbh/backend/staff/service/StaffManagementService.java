package com.nbh.backend.staff.service;

import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.staff.dto.StaffDtos.InviteRequest;
import com.nbh.backend.staff.dto.StaffDtos.RoleDto;
import com.nbh.backend.staff.dto.StaffDtos.StaffDto;
import com.nbh.backend.staff.entity.StaffRole;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Staff account management for the admin "Staff" tab: invite, list, change role, disable/reactivate.
 * Actually setting a password is handled entirely by {@link StaffPasswordResetService} — an invite
 * just creates the account and sends it a first sign-in code through that same OTP flow.
 */
@Service
public class StaffManagementService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
    private final SecureRandom random = new SecureRandom();

    private final StaffUserRepository staffUsers;
    private final StaffRoleRepository staffRoles;
    private final PasswordEncoder passwordEncoder;
    private final StaffPasswordResetService passwordResetService;

    public StaffManagementService(StaffUserRepository staffUsers, StaffRoleRepository staffRoles,
                                   PasswordEncoder passwordEncoder, StaffPasswordResetService passwordResetService) {
        this.staffUsers = staffUsers;
        this.staffRoles = staffRoles;
        this.passwordEncoder = passwordEncoder;
        this.passwordResetService = passwordResetService;
    }

    @Transactional(readOnly = true)
    public List<StaffDto> list() {
        return staffUsers.findByDeletedFalseOrderByCreatedAtDesc().stream().map(StaffDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<RoleDto> roles() {
        return staffRoles.findByDeletedFalseOrderByDisplayName().stream().map(RoleDto::from).toList();
    }

    @Transactional
    public StaffDto invite(InviteRequest request) {
        String email = request.email().trim().toLowerCase();
        if (staffUsers.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("A staff account with that email already exists");
        }
        StaffRole role = staffRoles.findByName(request.roleName())
                .orElseThrow(() -> new UnprocessableException("Unknown role: " + request.roleName()));

        StaffUser user = new StaffUser();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(generateUnusablePassword()));
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setStaffRole(role);
        user.setMustChangePassword(true);
        staffUsers.save(user);

        passwordResetService.issueInvite(user);
        return StaffDto.from(user);
    }

    @Transactional
    public StaffDto updateRole(UUID id, String roleName) {
        StaffUser user = find(id);
        StaffRole role = staffRoles.findByName(roleName)
                .orElseThrow(() -> new UnprocessableException("Unknown role: " + roleName));
        user.setStaffRole(role);
        return StaffDto.from(staffUsers.save(user));
    }

    @Transactional
    public StaffDto setEnabled(UUID id, boolean enabled, StaffUser actingStaff) {
        StaffUser user = find(id);
        if (!enabled && user.getId().equals(actingStaff.getId())) {
            throw new UnprocessableException("You can't disable your own account");
        }
        user.setEnabled(enabled);
        return StaffDto.from(staffUsers.save(user));
    }

    private StaffUser find(UUID id) {
        StaffUser user = staffUsers.findById(id).orElseThrow(() -> new NotFoundException("Staff member not found"));
        if (user.isDeleted()) {
            throw new NotFoundException("Staff member not found");
        }
        return user;
    }

    private String generateUnusablePassword() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
