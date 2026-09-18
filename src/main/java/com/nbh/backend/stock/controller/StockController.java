package com.nbh.backend.stock.controller;

import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.branch.repository.StockLocationRepository;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.dto.AdjustStockRequest;
import com.nbh.backend.stock.dto.InventoryLineDto;
import com.nbh.backend.stock.dto.ReceiveStockRequest;
import com.nbh.backend.stock.dto.StockBalanceDto;
import com.nbh.backend.stock.entity.*;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.service.StockDocumentNoGenerator;
import com.nbh.backend.stock.service.StockPostingService;
import com.nbh.backend.staff.service.StaffBranchAccessService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Thin HTTP layer over StockPostingService — builds a DRAFT StockDocument from
 * the request and posts it. All the actual invariants (locking, negative-stock
 * guard, double-entry) live in the service; this controller only translates
 * request shapes and permission checks.
 */
@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final StockPostingService postingService;
    private final StockLocationRepository locationRepository;
    private final ProductVariantRepository variantRepository;
    private final StockBalanceRepository balanceRepository;
    private final StockDocumentNoGenerator documentNoGenerator;
    private final StaffBranchAccessService branchAccessService;

    public StockController(StockPostingService postingService,
                            StockLocationRepository locationRepository,
                            ProductVariantRepository variantRepository,
                            StockBalanceRepository balanceRepository,
                            StockDocumentNoGenerator documentNoGenerator,
                            StaffBranchAccessService branchAccessService) {
        this.postingService = postingService;
        this.locationRepository = locationRepository;
        this.variantRepository = variantRepository;
        this.balanceRepository = balanceRepository;
        this.documentNoGenerator = documentNoGenerator;
        this.branchAccessService = branchAccessService;
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    @PostMapping("/receive")
    public StockDocumentSummary receive(@Valid @RequestBody ReceiveStockRequest request,
                                         @AuthenticationPrincipal com.nbh.backend.staff.entity.StaffUser staff) {
        // Audit finding F6 (session 3): neither endpoint checked isInternal(), so
        // a receipt into CUSTOMER or an adjustment against ADJUSTMENT/SUPPLIER
        // bypassed the negative-stock guard entirely (it only applies to internal
        // locations) and produced a balance row for a virtual location that has
        // no business existing outside what StockPostingService itself creates
        // as the "other side" of a real internal movement.
        StockLocation target = findInternalActiveLocationOrThrow(request.locationId());
        branchAccessService.assertCanAccessBranch(staff, target.getBranch().getId());
        StockLocation supplier = findLocationByCodeOrThrow("SUPPLIER");

        StockDocument document = new StockDocument();
        document.setDocumentNo(documentNoGenerator.next(StockDocumentType.GOODS_RECEIPT));
        document.setDocumentType(StockDocumentType.GOODS_RECEIPT);
        document.setBranch(target.getBranch());
        document.setNotes(request.notes());
        document.setPostedByUserId(staff.getId());

        int lineNo = 1;
        for (ReceiveStockRequest.Line line : request.lines()) {
            ProductVariant variant = findVariantOrThrow(line.variantId());
            StockDocumentLine docLine = new StockDocumentLine();
            docLine.setDocument(document);
            docLine.setLineNo(lineNo++);
            docLine.setVariant(variant);
            docLine.setSourceLocation(supplier);
            docLine.setTargetLocation(target);
            docLine.setQty(new BigDecimal(line.qty()));
            docLine.setUnitCost(new BigDecimal(line.unitCost()));
            document.getLines().add(docLine);
        }

        return StockDocumentSummary.from(postingService.post(document));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PostMapping("/adjust")
    public StockDocumentSummary adjust(@Valid @RequestBody AdjustStockRequest request,
                                        @AuthenticationPrincipal com.nbh.backend.staff.entity.StaffUser staff) {
        StockLocation location = findInternalActiveLocationOrThrow(request.locationId());
        branchAccessService.assertCanAccessBranch(staff, location.getBranch().getId());
        StockLocation adjustment = findLocationByCodeOrThrow("ADJUSTMENT");
        ProductVariant variant = findVariantOrThrow(request.variantId());

        BigDecimal qtyChange = new BigDecimal(request.qtyChange());
        if (qtyChange.signum() == 0) {
            throw new IllegalArgumentException("qtyChange must be non-zero");
        }

        StockDocument document = new StockDocument();
        document.setDocumentNo(documentNoGenerator.next(StockDocumentType.ADJUSTMENT));
        document.setDocumentType(StockDocumentType.ADJUSTMENT);
        document.setBranch(location.getBranch());
        document.setNotes(request.reason());
        document.setPostedByUserId(staff.getId());

        StockDocumentLine docLine = new StockDocumentLine();
        docLine.setDocument(document);
        docLine.setLineNo(1);
        docLine.setVariant(variant);
        docLine.setQty(qtyChange.abs());

        if (qtyChange.signum() > 0) {
            // Surplus found: ADJUSTMENT -> location. Needs a cost to update the average.
            if (request.unitCost() == null) {
                throw new IllegalArgumentException("unitCost is required for a positive adjustment");
            }
            docLine.setSourceLocation(adjustment);
            docLine.setTargetLocation(location);
            docLine.setUnitCost(new BigDecimal(request.unitCost()));
        } else {
            // Write-off/shrinkage: location -> ADJUSTMENT.
            docLine.setSourceLocation(location);
            docLine.setTargetLocation(adjustment);
        }
        document.getLines().add(docLine);

        return StockDocumentSummary.from(postingService.post(document));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping("/balance")
    @Transactional(readOnly = true)
    public List<StockBalanceDto> balance(@RequestParam(required = false) UUID variantId,
                                          @RequestParam(required = false) UUID locationId) {
        if (variantId != null && locationId != null) {
            return balanceRepository.findByVariantIdAndLocationId(variantId, locationId)
                    .map(b -> List.of(StockBalanceDto.from(b)))
                    .orElse(List.of());
        }
        if (variantId != null) {
            return balanceRepository.findByVariantId(variantId).stream().map(StockBalanceDto::from).toList();
        }
        if (locationId != null) {
            return balanceRepository.findByLocationId(locationId).stream().map(StockBalanceDto::from).toList();
        }
        throw new IllegalArgumentException("Provide at least one of variantId or locationId");
    }

    /**
     * The staff-facing "browse stock levels across the catalogue" list the
     * completeness tour found missing — see {@code StockBalanceRepository.browseInventory}'s
     * javadoc. Read-only, same permission as {@code balance()} above.
     */
    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping("/inventory")
    public Page<InventoryLineDto> inventory(@RequestParam(required = false) UUID branchId,
                                             @RequestParam(defaultValue = "false") boolean belowReorderOnly,
                                             @RequestParam(required = false) String q,
                                             @PageableDefault(size = 20) Pageable pageable) {
        return balanceRepository.browseInventory(branchId, belowReorderOnly, q, pageable);
    }

    private StockLocation findLocationOrThrow(UUID id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No stock location: " + id));
    }

    private StockLocation findInternalActiveLocationOrThrow(UUID id) {
        StockLocation location = findLocationOrThrow(id);
        if (!location.isInternal()) {
            throw new IllegalArgumentException(
                    "Location " + location.getCode() + " is virtual — receive/adjust must target a physical location");
        }
        if (!location.isActive()) {
            throw new IllegalArgumentException("Location " + location.getCode() + " is not active");
        }
        return location;
    }

    private StockLocation findLocationByCodeOrThrow(String code) {
        // §4 C7: a required seed row missing is a deployment problem, not
        // something the requesting staff member's input got wrong — 500.
        return locationRepository.findByCode(code)
                .orElseThrow(() -> new ConfigurationException("Missing seeded virtual location: " + code));
    }

    private ProductVariant findVariantOrThrow(UUID id) {
        return variantRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No product variant: " + id));
    }

    public record StockDocumentSummary(UUID id, String documentNo, String status) {
        static StockDocumentSummary from(StockDocument d) {
            return new StockDocumentSummary(d.getId(), d.getDocumentNo(), d.getStatus().name());
        }
    }
}
