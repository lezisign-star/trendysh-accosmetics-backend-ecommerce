package com.nbh.backend.kits.controller;

import com.nbh.backend.kits.dto.KitDtos.KitAdminDto;
import com.nbh.backend.kits.dto.KitDtos.SaveKitRequest;
import com.nbh.backend.kits.service.KitService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin CRUD for Professional Kits. Same permission as categories/products/promo campaigns: this
 * isn't its own concern, it's a form of product management (a kit IS a product, see
 * {@code KitService}'s javadoc).
 */
@RestController
@RequestMapping("/api/v1/admin/kits")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class KitController {

    private final KitService service;

    public KitController(KitService service) {
        this.service = service;
    }

    @GetMapping
    public Page<KitAdminDto> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(pageable);
    }

    @GetMapping("/{id}")
    public KitAdminDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public KitAdminDto create(@Valid @RequestBody SaveKitRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public KitAdminDto update(@PathVariable UUID id, @Valid @RequestBody SaveKitRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/active")
    public KitAdminDto setActive(@PathVariable UUID id, @RequestParam boolean active) {
        return service.setActive(id, active);
    }
}
