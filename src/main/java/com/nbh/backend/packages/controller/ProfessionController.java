package com.nbh.backend.packages.controller;

import com.nbh.backend.packages.dto.ProfessionDtos.ProfessionDto;
import com.nbh.backend.packages.dto.ProfessionDtos.ProfessionRequest;
import com.nbh.backend.packages.dto.ProfessionDtos.UpdateProfessionRequest;
import com.nbh.backend.packages.service.PackageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * A thin, separate controller delegating to {@link PackageService} — mirrors how
 * {@code SupplierController} sits beside {@code PurchaseOrderController} sharing its service. It backs the
 * Professions screen of the package admin (owner, 2026-10-24), as well as the package form's dropdown and "+ New".
 */
@RestController
@RequestMapping("/api/v1/admin/professions")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class ProfessionController {

    private final PackageService service;

    public ProfessionController(PackageService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProfessionDto> list() {
        return service.listProfessions();
    }

    @PostMapping
    public ProfessionDto create(@Valid @RequestBody ProfessionRequest request) {
        return service.createProfession(request);
    }

    /** A new name, description or picture (the link stays). */
    @PutMapping("/{id}")
    public ProfessionDto update(@PathVariable UUID id, @Valid @RequestBody UpdateProfessionRequest request) {
        return service.updateProfession(id, request);
    }

    /** Refused (422) while any package is listed under the trade. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.deleteProfession(id);
    }
}
