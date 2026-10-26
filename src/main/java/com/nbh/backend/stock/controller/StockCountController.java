package com.nbh.backend.stock.controller;

import com.nbh.backend.stock.dto.StockCountDtos.ApplyCountRequest;
import com.nbh.backend.stock.dto.StockCountDtos.CreateCountRequest;
import com.nbh.backend.stock.dto.StockCountDtos.RecordCountRequest;
import com.nbh.backend.stock.dto.StockCountDtos.RecordCountsRequest;
import com.nbh.backend.stock.dto.StockCountDtos.ScopeCountRequest;
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
    private final com.nbh.backend.catalog.repository.ProductImageRepository imageRepository;

    public StockCountController(StockCountService countService,
                                com.nbh.backend.catalog.repository.ProductImageRepository imageRepository) {
        this.countService = countService;
        this.imageRepository = imageRepository;
    }

    /** A photo per counted option: its own, else its product's main one, else the product's first (for the counting screen). */
    private Map<UUID, String> images(StockCount count) {
        java.util.Set<UUID> productIds = new java.util.HashSet<>();
        count.getLines().forEach(l -> productIds.add(l.getVariant().getProduct().getId()));
        if (productIds.isEmpty()) return Map.of();
        Map<UUID, String> byVariant = new java.util.HashMap<>();
        Map<UUID, String> byProduct = new java.util.HashMap<>();
        for (var image : imageRepository.findByProductIdInOrderBySortOrderAsc(productIds)) {
            String url = image.getThumbnailUrl() != null ? image.getThumbnailUrl() : image.getUrl();
            if (url == null) continue;
            if (image.getVariant() != null) byVariant.putIfAbsent(image.getVariant().getId(), url);
            UUID productId = image.getProduct().getId();
            if (image.isPrimary()) byProduct.put(productId, url); else byProduct.putIfAbsent(productId, url);
        }
        Map<UUID, String> result = new java.util.HashMap<>();
        for (var line : count.getLines()) {
            UUID variantId = line.getVariant().getId();
            String url = byVariant.getOrDefault(variantId, byProduct.get(line.getVariant().getProduct().getId()));
            if (url != null) result.put(variantId, url);
        }
        return result;
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
        StockCount count = countService.get(id);
        return StockCountDto.from(count, canAdjust(staff), images(count));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_COUNT') or hasAuthority('PERM_STOCK_ADJUST')")
    @PostMapping
    @Transactional
    public StockCountDto create(@Valid @RequestBody CreateCountRequest request, @AuthenticationPrincipal StaffUser staff) {
        StockCount count = countService.create(
                request.variantIds(), request.blind(), request.reason(), request.assignedTo(), staff.getId());
        return StockCountDto.from(count, canAdjust(staff), images(count));
    }

    /** A stock take of the whole shop, some categories or some products (owner, 2026-10-26). */
    @PreAuthorize("hasAuthority('PERM_STOCK_COUNT') or hasAuthority('PERM_STOCK_ADJUST')")
    @PostMapping("/for-scope")
    @Transactional
    public StockCountDto createForScope(@RequestBody ScopeCountRequest request, @AuthenticationPrincipal StaffUser staff) {
        StockCount count = countService.createForScope(request.everything(), request.categoryIds(), request.productIds(),
                request.blind(), request.reason(), staff.getId());
        return StockCountDto.from(count, canAdjust(staff), images(count));
    }

    /** Several lines at once ("set all its options to 20"). */
    @PreAuthorize("hasAuthority('PERM_STOCK_COUNT') or hasAuthority('PERM_STOCK_ADJUST')")
    @PutMapping("/{id}/lines")
    @Transactional
    public StockCountDto recordCounts(@PathVariable UUID id, @Valid @RequestBody RecordCountsRequest request,
                                      @AuthenticationPrincipal StaffUser staff) {
        StockCount count = countService.recordCounts(id, request.counted(), staff.getId());
        return StockCountDto.from(count, canAdjust(staff), images(count));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_COUNT') or hasAuthority('PERM_STOCK_ADJUST')")
    @PutMapping("/{id}/lines/{variantId}")
    @Transactional
    public StockCountDto recordCount(@PathVariable UUID id, @PathVariable UUID variantId,
                                      @Valid @RequestBody RecordCountRequest request,
                                      @AuthenticationPrincipal StaffUser staff) {
        StockCount count = countService.recordCount(id, variantId, request.countedQty(), request.note(), staff.getId());
        return StockCountDto.from(count, canAdjust(staff), images(count));
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
