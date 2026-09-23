package com.nbh.backend.purchasing.controller;

import com.nbh.backend.purchasing.dto.SupplierDtos.SupplierDto;
import com.nbh.backend.purchasing.dto.SupplierDtos.SupplierRequest;
import com.nbh.backend.purchasing.service.PurchaseOrderService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/suppliers")
public class SupplierController {

    private final PurchaseOrderService service;

    public SupplierController(PurchaseOrderService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping
    public List<SupplierDto> list() {
        return service.listSuppliers();
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    @PostMapping
    public SupplierDto create(@Valid @RequestBody SupplierRequest request) {
        return service.createSupplier(request);
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    @PatchMapping("/{id}")
    public SupplierDto update(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        return service.updateSupplier(id, request);
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.deleteSupplier(id);
    }
}
