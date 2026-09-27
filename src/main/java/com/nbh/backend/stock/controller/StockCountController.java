package com.nbh.backend.stock.controller;

import com.nbh.backend.stock.dto.StockCountDtos.ApplyCountRequest;
import com.nbh.backend.stock.dto.StockCountDtos.CreateCountRequest;
import com.nbh.backend.stock.dto.StockCountDtos.RecordCountRequest;
import com.nbh.backend.stock.dto.StockCountDtos.StockCountDto;
import com.nbh.backend.stock.entity.StockCount;
import com.nbh.backend.stock.entity.StockCountStatus;
import com.nbh.backend.stock.service.StockCountService;
import com.nbh.backend.stock.service.StockCountService.ConflictResolution;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 2 (2026-09-27): physical stock counts — see {@link StockCountService}'s javadoc for the
 * workflow. Recording needs only PERM_STOCK_COUNT (a counter's job is counting, not adjusting);
 * applying/cancelling need PERM_STOCK_ADJUST, since that's the step that actually posts stock.
 */
@RestController
@RequestMapping("/api/v1/stock/counts")
public class StockCountController {

    private static final String ADJUST_AUTHORITY = "PERM_STOCK_ADJUST";

    private final StockCountService countService;

    public StockCountController(StockCountService countService) {
        this.countService = countService;
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_COUNT') or hasAuthority('PERM_STOCK_ADJUST')")
    @GetMapping
    @Transactional(readOnly = true)
    public Page<StockCountDto> list(@RequestParam(required = false) StockCountStatus status,
                                     @PageableDefault(size = 20) Pageable pageable,
                                     @AuthenticationPrincipal StaffUser staff) {
        boolean revealSystemQty = canAdjust(staff);
        return countService.list(status, pageable).map(c -> StockCountDto.from(c, revealSystemQty));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_COUNT') or hasAuthority('PERM_STOCK_ADJUST')")
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public StockCountDto get(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        return StockCountDto.from(countService.get(id), canAdjust(staff));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_COUNT') or hasAuthority('PERM_STOCK_ADJUST')")
    @PostMapping
    @Transactional
    public StockCountDto create(@Valid @RequestBody CreateCountRequest request, @AuthenticationPrincipal StaffUser staff) {
        StockCount count = countService.create(
                request.variantIds(), request.blind(), request.reason(), request.assignedTo(), staff.getId());
        return StockCountDto.from(count, canAdjust(staff));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_COUNT') or hasAuthority('PERM_STOCK_ADJUST')")
    @PutMapping("/{id}/lines/{variantId}")
    @Transactional
    public StockCountDto recordCount(@PathVariable UUID id, @PathVariable UUID variantId,
                                      @Valid @RequestBody RecordCountRequest request,
                                      @AuthenticationPrincipal StaffUser staff) {
        StockCount count = countService.recordCount(id, variantId, request.countedQty(), request.note(), staff.getId());
        return StockCountDto.from(count, canAdjust(staff));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PostMapping("/{id}/apply")
    @Transactional
    public StockCountDto apply(@PathVariable UUID id, @RequestBody(required = false) ApplyCountRequest request,
                                @AuthenticationPrincipal StaffUser staff) {
        Map<UUID, ConflictResolution> resolutions = new java.util.HashMap<>();
        if (request != null && request.resolutions() != null) {
            request.resolutions().forEach((variantId, value) ->
                    resolutions.put(variantId, ConflictResolution.valueOf(value)));
        }
        StockCount count = countService.apply(id, resolutions, staff.getId());
        return StockCountDto.from(count, true);
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PostMapping("/{id}/cancel")
    @Transactional
    public StockCountDto cancel(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        return StockCountDto.from(countService.cancel(id), true);
    }

    private static boolean canAdjust(StaffUser staff) {
        return staff.getAuthorities().stream().anyMatch(a -> ADJUST_AUTHORITY.equals(a.getAuthority()));
    }
}
