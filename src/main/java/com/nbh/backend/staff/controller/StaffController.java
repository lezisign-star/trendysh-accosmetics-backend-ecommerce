package com.nbh.backend.staff.controller;

import com.nbh.backend.staff.dto.StaffDtos.InviteRequest;
import com.nbh.backend.staff.dto.StaffDtos.RoleDto;
import com.nbh.backend.staff.dto.StaffDtos.RoleUpdateRequest;
import com.nbh.backend.staff.dto.StaffDtos.StaffDto;
import com.nbh.backend.staff.dto.StaffDtos.StatusUpdateRequest;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.service.StaffManagementService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The admin "Staff" tab: who has a dashboard login, what role they have, and inviting new staff. */
@RestController
@RequestMapping("/api/v1/admin/staff")
public class StaffController {

    private final StaffManagementService service;

    public StaffController(StaffManagementService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('PERM_USER_VIEW')")
    @GetMapping
    public List<StaffDto> list() {
        return service.list();
    }

    @PreAuthorize("hasAuthority('PERM_USER_VIEW')")
    @GetMapping("/roles")
    public List<RoleDto> roles(@AuthenticationPrincipal StaffUser staff) {
        return service.roles(staff);
    }

    @PreAuthorize("hasAuthority('PERM_USER_CREATE')")
    @PostMapping("/invite")
    public StaffDto invite(@Valid @RequestBody InviteRequest body, @AuthenticationPrincipal StaffUser staff) {
        return service.invite(body, staff);
    }

    @PreAuthorize("hasAuthority('PERM_USER_MANAGE_ROLES')")
    @PatchMapping("/{id}/role")
    public StaffDto updateRole(@PathVariable UUID id, @Valid @RequestBody RoleUpdateRequest body,
                                @AuthenticationPrincipal StaffUser staff) {
        return service.updateRole(id, body.roleName(), staff);
    }

    @PreAuthorize("hasAuthority('PERM_USER_MANAGE_ROLES')")
    @PatchMapping("/{id}/status")
    public StaffDto updateStatus(@PathVariable UUID id, @Valid @RequestBody StatusUpdateRequest body,
                                  @AuthenticationPrincipal StaffUser staff) {
        return service.setEnabled(id, body.enabled(), staff);
    }
}
