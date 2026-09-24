package com.nbh.backend.stock.controller;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.dto.AdjustStockRequest;
import com.nbh.backend.stock.dto.InventoryLineDto;
import com.nbh.backend.stock.dto.ReceiveStockRequest;
import com.nbh.backend.stock.dto.StockBalanceDto;
import com.nbh.backend.stock.entity.*;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.service.StockAvailabilityService;
import com.nbh.backend.stock.service.StockDocumentNoGenerator;
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
    private final StockBalanceRepository balanceRepository;
    private final StockAvailabilityService availabilityService;
    private final StockDocumentNoGenerator documentNoGenerator;

    public StockController(StockPostingService postingService,
                            ProductVariantRepository variantRepository,
                            StockBalanceRepository balanceRepository,
                            StockAvailabilityService availabilityService,
                            StockDocumentNoGenerator documentNoGenerator) {
        this.postingService = postingService;
        this.variantRepository = variantRepository;
        this.balanceRepository = balanceRepository;
        this.availabilityService = availabilityService;
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
            StockDocumentLine docLine = new StockDocumentLine();
            docLine.setDocument(document);
            docLine.setLineNo(lineNo++);
            docLine.setVariant(variant);
            docLine.setSourceAccount(StockAccount.SUPPLIER);
            docLine.setTargetAccount(StockAccount.SHOP);
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
            docLine.setSourceAccount(StockAccount.ADJUSTMENT);
            docLine.setTargetAccount(StockAccount.SHOP);
            docLine.setUnitCost(new BigDecimal(request.unitCost()));
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
        return balanceRepository.findByVariantId(variantId).stream().map(StockBalanceDto::from).toList();
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
