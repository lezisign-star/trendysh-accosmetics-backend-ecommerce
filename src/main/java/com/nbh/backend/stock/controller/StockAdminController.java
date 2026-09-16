package com.nbh.backend.stock.controller;

import com.nbh.backend.stock.service.StockBalanceRebuildService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit finding F7 (session 3): the design doc (§4.4) calls for a
 * POST /admin/stock/rebuild-balances endpoint; it did not exist. This is a
 * read-only consistency CHECK, not a destructive rebuild — stock_balance is
 * never written here. If it ever reports a mismatch, that is a bug to fix in
 * StockPostingService, not something to silently patch over by overwriting
 * the cache from this endpoint.
 */
@RestController
@RequestMapping("/api/v1/admin/stock")
public class StockAdminController {

    private final StockBalanceRebuildService rebuildService;

    public StockAdminController(StockBalanceRebuildService rebuildService) {
        this.rebuildService = rebuildService;
    }

    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @GetMapping("/consistency-check")
    public StockBalanceRebuildService.RebuildReport consistencyCheck() {
        return rebuildService.check();
    }
}
