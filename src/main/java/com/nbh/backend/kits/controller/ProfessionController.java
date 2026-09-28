package com.nbh.backend.kits.controller;

import com.nbh.backend.kits.dto.ProfessionDtos.ProfessionDto;
import com.nbh.backend.kits.dto.ProfessionDtos.ProfessionRequest;
import com.nbh.backend.kits.service.KitService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A thin, separate controller delegating to {@link KitService} — mirrors how
 * {@code SupplierController} sits beside {@code PurchaseOrderController} sharing its service. No
 * dedicated admin screen for professions (client decision): this only backs the kit form's
 * dropdown + inline "+ New" quick-add, so no update/delete surface exists yet.
 */
@RestController
@RequestMapping("/api/v1/admin/professions")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class ProfessionController {

    private final KitService service;

    public ProfessionController(KitService service) {
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
}
