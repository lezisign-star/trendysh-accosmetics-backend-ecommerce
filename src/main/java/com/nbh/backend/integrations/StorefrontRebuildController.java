package com.nbh.backend.integrations;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff view of, and button for, the storefront rebuild (see {@link StorefrontRebuildService}). */
@RestController
@RequestMapping("/api/v1/admin/storefront/rebuild")
@PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
public class StorefrontRebuildController {

    private final StorefrontRebuildService service;

    public StorefrontRebuildController(StorefrontRebuildService service) {
        this.service = service;
    }

    @GetMapping
    public StorefrontRebuildService.Status status() {
        return service.status();
    }

    /** Rebuild now (throttled to once every few minutes). */
    @PostMapping
    public StorefrontRebuildService.Status rebuild() {
        return service.rebuild("manual", true);
    }
}
