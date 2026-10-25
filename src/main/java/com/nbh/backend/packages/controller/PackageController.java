package com.nbh.backend.packages.controller;

import com.nbh.backend.catalog.dto.AdminImageDto;
import com.nbh.backend.packages.dto.PackageDtos.AiPhotoRequest;
import com.nbh.backend.packages.dto.PackageDtos.PackageAdminDto;
import com.nbh.backend.packages.dto.PackageDtos.SavePackageRequest;
import com.nbh.backend.packages.service.PackageAiPhotoService;
import com.nbh.backend.packages.service.PackageService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin CRUD for Professional Packages. Same permission as categories/products/promo campaigns: this
 * isn't its own concern, it's a form of product management (a package IS a product, see
 * {@code PackageService}'s javadoc).
 */
@RestController
@RequestMapping("/api/v1/admin/packages")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class PackageController {

    private final PackageService service;
    private final PackageAiPhotoService aiPhotos;

    public PackageController(PackageService service, PackageAiPhotoService aiPhotos) {
        this.service = service;
        this.aiPhotos = aiPhotos;
    }

    /**
     * Makes the package's picture with Gemini from its products' photos and files it with the package's photos (the thumbnail
     * when the package has none yet). Takes a while (Gemini answers in tens of seconds); see {@link PackageAiPhotoService}.
     */
    @PostMapping("/{id}/ai-photo")
    public AdminImageDto makeAiPhoto(@PathVariable UUID id, @Valid @RequestBody AiPhotoRequest request) {
        return aiPhotos.make(id, request.style(), request.note());
    }

    @GetMapping
    public Page<PackageAdminDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID professionId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(q, professionId, pageable);
    }

    @GetMapping("/{id}")
    public PackageAdminDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public PackageAdminDto create(@Valid @RequestBody SavePackageRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public PackageAdminDto update(@PathVariable UUID id, @Valid @RequestBody SavePackageRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/active")
    public PackageAdminDto setActive(@PathVariable UUID id, @RequestParam boolean active) {
        return service.setActive(id, active);
    }
}
