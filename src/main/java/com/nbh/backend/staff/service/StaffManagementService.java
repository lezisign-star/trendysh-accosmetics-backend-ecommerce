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

    private static final String DEVELOPER_ROLE = "DEVELOPER";
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";
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

    /** The roles {@code actor} may hand out: everyone but a developer never sees DEVELOPER offered. */
    @Transactional(readOnly = true)
    public List<RoleDto> roles(StaffUser actor) {
        return staffRoles.findByDeletedFalseOrderByDisplayName().stream()
                .filter(r -> isDeveloper(actor) || !DEVELOPER_ROLE.equals(r.getName()))
                .map(RoleDto::from)
                .toList();
    }

    @Transactional
    public StaffDto invite(InviteRequest request, StaffUser actor) {
        String email = request.email().trim().toLowerCase();
        if (staffUsers.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("A staff account with that email already exists");
        }
        StaffRole role = staffRoles.findByName(request.roleName())
                .orElseThrow(() -> new UnprocessableException("Unknown role: " + request.roleName()));
        requireMayAssign(role, actor);

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
    public StaffDto updateRole(UUID id, String roleName, StaffUser actor) {
        StaffUser user = find(id);
        StaffRole role = staffRoles.findByName(roleName)
                .orElseThrow(() -> new UnprocessableException("Unknown role: " + roleName));
        if (user.getId().equals(actor.getId())) {
            throw new UnprocessableException("You can't change your own role");
        }
        requireMayManage(user, actor);
        requireMayAssign(role, actor);
        if (SUPER_ADMIN_ROLE.equals(user.getStaffRole().getName()) && !SUPER_ADMIN_ROLE.equals(role.getName())) {
            requireAnotherActiveSuperAdmin(user);
        }
        user.setStaffRole(role);
        return StaffDto.from(staffUsers.save(user));
    }

    @Transactional
    public StaffDto setEnabled(UUID id, boolean enabled, StaffUser actingStaff) {
        StaffUser user = find(id);
        if (!enabled && user.getId().equals(actingStaff.getId())) {
            throw new UnprocessableException("You can't disable your own account");
        }
        requireMayManage(user, actingStaff);
        if (!enabled && user.isEnabled() && SUPER_ADMIN_ROLE.equals(user.getStaffRole().getName())) {
            requireAnotherActiveSuperAdmin(user);
        }
        user.setEnabled(enabled);
        return StaffDto.from(staffUsers.save(user));
    }

    private static boolean isDeveloper(StaffUser user) {
        return user.getStaffRole() != null && DEVELOPER_ROLE.equals(user.getStaffRole().getName());
    }

    /** The developer's own account is off limits to everyone else, including the client's owner. */
    private static void requireMayManage(StaffUser target, StaffUser actor) {
        if (isDeveloper(target) && !isDeveloper(actor)) {
            throw new UnprocessableException("The developer account can't be changed from here");
        }
    }

    /** Handing out DEVELOPER would unlock developer-only tools (e.g. the demo-data toggle), so only a developer may. */
    private static void requireMayAssign(StaffRole role, StaffUser actor) {
        if (DEVELOPER_ROLE.equals(role.getName()) && !isDeveloper(actor)) {
            throw new UnprocessableException("Only a developer can assign the Developer role");
        }
    }

    /** Stops the shop being left with nobody who can manage staff. */
    private void requireAnotherActiveSuperAdmin(StaffUser leaving) {
        if (staffUsers.countByStaffRoleNameAndEnabledTrueAndDeletedFalseAndIdNot(SUPER_ADMIN_ROLE, leaving.getId()) == 0) {
            throw new UnprocessableException("There must always be at least one active Super Admin");
        }
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
