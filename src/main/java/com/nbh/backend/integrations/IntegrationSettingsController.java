package com.nbh.backend.integrations;

import com.nbh.backend.integrations.IntegrationSettingsService.Status;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin screen for third-party credentials. Values go in, are stored encrypted, and are never sent back. */
@RestController
@RequestMapping("/api/v1/admin/settings/integrations")
@PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
public class IntegrationSettingsController {

    public record SetRequest(@NotBlank @Size(max = 500) String value) {}

    private final IntegrationSettingsService service;

    public IntegrationSettingsController(IntegrationSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public List<Status> list() {
        return service.statuses();
    }

    @PutMapping("/{key}")
    public List<Status> set(@PathVariable IntegrationKey key, @Valid @RequestBody SetRequest request,
                            @AuthenticationPrincipal StaffUser staff) {
        service.set(key, request.value(), staff.getId());
        return service.statuses();
    }

    @DeleteMapping("/{key}")
    public List<Status> clear(@PathVariable IntegrationKey key) {
        service.clear(key);
        return service.statuses();
    }
}
