package com.nbh.backend.purchasing.controller;

import com.nbh.backend.purchasing.dto.PurchaseOrderDtos.CreatePurchaseOrderRequest;
import com.nbh.backend.purchasing.dto.PurchaseOrderDtos.PurchaseOrderDto;
import com.nbh.backend.purchasing.dto.PurchaseOrderDtos.ReceivePurchaseOrderRequest;
import com.nbh.backend.purchasing.entity.PurchaseOrderStatus;
import com.nbh.backend.purchasing.service.PurchaseOrderService;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping
    public Page<PurchaseOrderDto> list(
            @RequestParam(required = false) PurchaseOrderStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(status, pageable);
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping("/{id}")
    public PurchaseOrderDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    @PostMapping
    public PurchaseOrderDto create(@Valid @RequestBody CreatePurchaseOrderRequest request,
                                    @AuthenticationPrincipal StaffUser staff) {
        return service.create(request, staff);
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    @PostMapping("/{id}/send")
    public PurchaseOrderDto markSent(@PathVariable UUID id) {
        return service.markSent(id);
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    @PostMapping("/{id}/cancel")
    public PurchaseOrderDto cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    @PostMapping("/{id}/receive")
    public PurchaseOrderDto receive(@PathVariable UUID id, @Valid @RequestBody ReceivePurchaseOrderRequest request,
                                     @AuthenticationPrincipal StaffUser staff) {
        return service.receive(id, request, staff);
    }
}
