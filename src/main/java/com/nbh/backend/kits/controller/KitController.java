package com.nbh.backend.kits.controller;

import com.nbh.backend.catalog.dto.AdminImageDto;
import com.nbh.backend.kits.dto.KitDtos.AiPhotoRequest;
import com.nbh.backend.kits.dto.KitDtos.KitAdminDto;
import com.nbh.backend.kits.dto.KitDtos.SaveKitRequest;
import com.nbh.backend.kits.service.KitAiPhotoService;
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
    private final KitAiPhotoService aiPhotos;

    public KitController(KitService service, KitAiPhotoService aiPhotos) {
        this.service = service;
        this.aiPhotos = aiPhotos;
    }

    /**
     * Makes the kit's picture with Gemini from its products' photos and files it with the kit's photos (the thumbnail
     * when the kit has none yet). Takes a while (Gemini answers in tens of seconds); see {@link KitAiPhotoService}.
     */
    @PostMapping("/{id}/ai-photo")
    public AdminImageDto makeAiPhoto(@PathVariable UUID id, @Valid @RequestBody AiPhotoRequest request) {
        return aiPhotos.make(id, request.style(), request.note());
    }

    @GetMapping
    public Page<KitAdminDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID professionId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(q, professionId, pageable);
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
