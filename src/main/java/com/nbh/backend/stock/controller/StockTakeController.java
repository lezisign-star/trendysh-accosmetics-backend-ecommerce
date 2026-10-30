package com.nbh.backend.stock.controller;

import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.service.StockTakeService;
import com.nbh.backend.stock.service.StockTakeService.CountView;
import com.nbh.backend.stock.service.StockTakeService.TakeView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The stock take, category by category (owner, 2026-10-30), for anyone who counts stock (one person or several at once);
 * finishing a category applies it. See {@link StockTakeService}.
 */
@RestController
@RequestMapping("/api/v1/stock/takes")
@PreAuthorize("hasAuthority('PERM_STOCK_COUNT') or hasAuthority('PERM_STOCK_ADJUST')")
public class StockTakeController {

    private final StockTakeService takes;

    public StockTakeController(StockTakeService takes) {
        this.takes = takes;
    }

    /** The stock take open now, or {@code {"take": null}}. */
    @GetMapping("/current")
    public Map<String, Object> current() {
        return java.util.Collections.singletonMap("take", takes.current().orElse(null));
    }

    @PostMapping
    public TakeView start(@AuthenticationPrincipal StaffUser staff) {
        return takes.start(staff);
    }

    @GetMapping("/{id}")
    public TakeView get(@PathVariable UUID id) {
        return takes.get(id);
    }

    @PostMapping("/{id}/close")
    public TakeView close(@PathVariable UUID id) {
        return takes.close(id);
    }

    @PostMapping("/{id}/categories/{categoryId}/open")
    public CountView open(@PathVariable UUID id, @PathVariable UUID categoryId,
                          @AuthenticationPrincipal StaffUser staff) {
        return takes.openCategory(id, categoryId, staff);
    }

    @GetMapping("/counts/{countId}")
    public CountView count(@PathVariable UUID countId) {
        return takes.countView(countId);
    }

    @PutMapping("/counts/{countId}/lines/{variantId}")
    public CountView record(@PathVariable UUID countId, @PathVariable UUID variantId,
                            @RequestBody Map<String, BigDecimal> body, @AuthenticationPrincipal StaffUser staff) {
        return takes.record(countId, variantId, body.get("qty"), staff);
    }

    @PostMapping("/counts/{countId}/lines/{variantId}/skip")
    public CountView skip(@PathVariable UUID countId, @PathVariable UUID variantId,
                          @AuthenticationPrincipal StaffUser staff) {
        return takes.skip(countId, variantId, staff);
    }

    @PostMapping("/counts/{countId}/set-all")
    public CountView setAll(@PathVariable UUID countId, @RequestBody StockTakeService.SetAll body,
                            @AuthenticationPrincipal StaffUser staff) {
        return takes.setAll(countId, body, staff);
    }

    @PostMapping("/counts/{countId}/items")
    public CountView addItem(@PathVariable UUID countId, @RequestBody StockTakeService.NewItem body,
                             @AuthenticationPrincipal StaffUser staff) {
        return takes.addItem(countId, body, staff);
    }

    @PostMapping("/counts/{countId}/finish")
    public CountView finish(@PathVariable UUID countId, @AuthenticationPrincipal StaffUser staff) {
        return takes.finish(countId, staff);
    }
}
