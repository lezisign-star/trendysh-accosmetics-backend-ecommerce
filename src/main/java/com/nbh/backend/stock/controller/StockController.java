package com.nbh.backend.stock.controller;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.dto.AdjustStockRequest;
import com.nbh.backend.stock.dto.InventoryLineDto;
import com.nbh.backend.stock.dto.ReceiveStockRequest;
import com.nbh.backend.stock.dto.StockBalanceDto;
import com.nbh.backend.stock.dto.StockDocumentDetailDto;
import com.nbh.backend.stock.dto.StockMovementDto;
import com.nbh.backend.stock.entity.*;
import com.nbh.backend.stock.service.StockAvailabilityService;
import com.nbh.backend.stock.service.StockDocumentNoGenerator;
import com.nbh.backend.stock.service.StockMovementQueryService;
import com.nbh.backend.stock.service.StockPostingService;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Thin HTTP layer over StockPostingService — builds a DRAFT StockDocument from
 * the request and posts it. All the actual invariants (locking, negative-stock
 * guard, double-entry) live in the service; this controller only translates
 * request shapes and permission checks. There is one shop (SHOP), so receive/
 * adjust no longer take a location — branches were removed 2026-09-24
 * (founder decision — see HANDOFF.md).
 */
@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final StockPostingService postingService;
    private final ProductVariantRepository variantRepository;
    private final StockAvailabilityService availabilityService;
    private final StockMovementQueryService movementQueryService;
    private final StockDocumentNoGenerator documentNoGenerator;

    public StockController(StockPostingService postingService,
                            ProductVariantRepository variantRepository,
                            StockAvailabilityService availabilityService,
                            StockMovementQueryService movementQueryService,
                            StockDocumentNoGenerator documentNoGenerator) {
        this.postingService = postingService;
        this.variantRepository = variantRepository;
        this.availabilityService = availabilityService;
        this.movementQueryService = movementQueryService;
        this.documentNoGenerator = documentNoGenerator;
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_RECEIVE')")
    @PostMapping("/receive")
    public StockDocumentSummary receive(@Valid @RequestBody ReceiveStockRequest request,
                                         @AuthenticationPrincipal com.nbh.backend.staff.entity.StaffUser staff) {
        StockDocument document = new StockDocument();
        document.setDocumentNo(documentNoGenerator.next(StockDocumentType.GOODS_RECEIPT));
        document.setDocumentType(StockDocumentType.GOODS_RECEIPT);
        document.setNotes(request.notes());
        document.setPostedByUserId(staff.getId());

        int lineNo = 1;
        for (ReceiveStockRequest.Line line : request.lines()) {
            ProductVariant variant = findVariantOrThrow(line.variantId());
            BigDecimal qty = new BigDecimal(line.qty());
            BigDecimal unitCost = new BigDecimal(line.unitCost());
            // Phase 0 fix (2026-09-27), D8: neither was validated server-side before — only
            // the admin frontend checked, which a direct API call bypasses entirely.
            if (qty.signum() <= 0) {
                throw new IllegalArgumentException(variant.getSku() + ": qty must be positive");
            }
            if (unitCost.signum() < 0) {
                throw new IllegalArgumentException(variant.getSku() + ": unitCost cannot be negative");
            }
            StockDocumentLine docLine = new StockDocumentLine();
            docLine.setDocument(document);
            docLine.setLineNo(lineNo++);
            docLine.setVariant(variant);
            docLine.setSourceAccount(StockAccount.SUPPLIER);
            docLine.setTargetAccount(StockAccount.SHOP);
            docLine.setQty(qty);
            docLine.setUnitCost(unitCost);
            document.getLines().add(docLine);
        }

        return StockDocumentSummary.from(postingService.post(document));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PostMapping("/adjust")
    public StockDocumentSummary adjust(@Valid @RequestBody AdjustStockRequest request,
                                        @AuthenticationPrincipal com.nbh.backend.staff.entity.StaffUser staff) {
        ProductVariant variant = findVariantOrThrow(request.variantId());

        BigDecimal qtyChange = new BigDecimal(request.qtyChange());
        if (qtyChange.signum() == 0) {
            throw new IllegalArgumentException("qtyChange must be non-zero");
        }

        StockDocument document = new StockDocument();
        document.setDocumentNo(documentNoGenerator.next(StockDocumentType.ADJUSTMENT));
        document.setDocumentType(StockDocumentType.ADJUSTMENT);
        document.setNotes(request.reason());
        document.setPostedByUserId(staff.getId());

        StockDocumentLine docLine = new StockDocumentLine();
        docLine.setDocument(document);
        docLine.setLineNo(1);
        docLine.setVariant(variant);
        docLine.setQty(qtyChange.abs());

        if (qtyChange.signum() > 0) {
            // Surplus found: ADJUSTMENT -> SHOP. Needs a cost to update the average.
            if (request.unitCost() == null) {
                throw new IllegalArgumentException("unitCost is required for a positive adjustment");
            }
            BigDecimal unitCost = new BigDecimal(request.unitCost());
            // Phase 0 fix (2026-09-27), D8: wasn't validated server-side before.
            if (unitCost.signum() < 0) {
                throw new IllegalArgumentException("unitCost cannot be negative");
            }
            docLine.setSourceAccount(StockAccount.ADJUSTMENT);
            docLine.setTargetAccount(StockAccount.SHOP);
            docLine.setUnitCost(unitCost);
        } else {
            // Write-off/shrinkage: SHOP -> ADJUSTMENT.
            docLine.setSourceAccount(StockAccount.SHOP);
            docLine.setTargetAccount(StockAccount.ADJUSTMENT);
        }
        document.getLines().add(docLine);

        return StockDocumentSummary.from(postingService.post(document));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping("/balance")
    @Transactional(readOnly = true)
    public List<StockBalanceDto> balance(@RequestParam UUID variantId) {
        return availabilityService.balancesForVariant(variantId).stream().map(StockBalanceDto::from).toList();
    }

    /**
     * The staff-facing "browse stock levels across the catalogue" list the
     * completeness tour found missing — see {@code StockBalanceRepository.browseInventory}'s
     * javadoc. Read-only, same permission as {@code balance()} above.
     */
    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping("/inventory")
    public Page<InventoryLineDto> inventory(@RequestParam(defaultValue = "false") boolean belowReorderOnly,
                                             @RequestParam(required = false) String q,
                                             @PageableDefault(size = 20) Pageable pageable) {
        return availabilityService.browse(q, belowReorderOnly, pageable);
    }

    /**
     * Phase 1 (2026-09-27): the movement-history view — the ledger has always had this data,
     * nothing had ever read it back. {@code from}/{@code to} are optional; an absent one
     * resolves to "the beginning of time" / "now" (see StockMovementQueryService).
     */
    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping("/movements")
    public Page<StockMovementDto> movements(@RequestParam(required = false) UUID variantId,
                                             @RequestParam(required = false) StockDocumentType documentType,
                                             @RequestParam(required = false) Instant from,
                                             @RequestParam(required = false) Instant to,
                                             @PageableDefault(size = 20) Pageable pageable) {
        return movementQueryService.search(variantId, documentType, from, to, pageable);
    }

    /** Phase 1 (2026-09-27): one document's own header plus its ledger entries. */
    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping("/documents/{id}")
    public StockDocumentDetailDto document(@PathVariable UUID id) {
        return movementQueryService.document(id);
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
