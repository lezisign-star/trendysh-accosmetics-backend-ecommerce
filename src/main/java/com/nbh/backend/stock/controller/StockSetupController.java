package com.nbh.backend.stock.controller;

import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.service.StockSetupService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The Stock tab's "Set many at once" (owner, 2026-10-26): see {@link StockSetupService}. Reading needs STOCK_VIEW; changing
 * a number or a switch needs STOCK_ADJUST, as setting one option's stock does.
 */
@RestController
@RequestMapping("/api/v1/stock/setup")
public class StockSetupController {

    public record SwitchRequest(boolean on) {}

    public record SetManyRequest(boolean everything, List<UUID> categoryIds, List<UUID> productIds, BigDecimal qty,
                                 boolean overwrite, boolean preview) {}

    private final StockSetupService setup;

    public StockSetupController(StockSetupService setup) {
        this.setup = setup;
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW') or hasAuthority('PERM_STOCK_ADJUST')")
    @GetMapping
    public StockSetupService.Summary summary() {
        return setup.summary();
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PutMapping("/everywhere")
    public StockSetupService.Summary everywhere(@RequestBody SwitchRequest request) {
        setup.sellWithoutStockEverywhere(request.on());
        return setup.summary();
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PutMapping("/categories/{id}")
    public StockSetupService.Summary category(@PathVariable UUID id, @RequestBody SwitchRequest request) {
        setup.sellWithoutStockInCategory(id, request.on());
        return setup.summary();
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PutMapping("/products/{id}")
    public void product(@PathVariable UUID id, @RequestBody SwitchRequest request) {
        setup.sellWithoutStockForProduct(id, request.on());
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PostMapping("/set-many")
    public StockSetupService.BulkSetResult setMany(@RequestBody SetManyRequest request,
                                                    @AuthenticationPrincipal StaffUser staff) {
        return setup.setMany(request.everything(), request.categoryIds(), request.productIds(), request.qty(),
                request.overwrite(), request.preview(), staff);
    }
}
