package com.nbh.backend.stock.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Set this variant's stock to N" for the product editor — the everyday case of a shop owner typing a
 * quantity, without knowing about receipts or adjustments. The difference from what is sellable now
 * is posted through {@link StockPostingService} (the single write path), so the ledger stays a
 * complete, balanced audit trail:
 * <ul>
 *   <li>more stock than before: a GOODS_RECEIPT from SUPPLIER into SHOP;</li>
 *   <li>less stock: an ADJUSTMENT write-off out of SHOP.</li>
 * </ul>
 * The target is the SELLABLE quantity (on hand minus reserved), i.e. exactly the number the editor
 * shows, so a pending checkout reservation never causes a wrong correction. Branches/locations were
 * removed 2026-09-24 (founder decision — see HANDOFF.md); there is one shop, so no location lookup
 * is needed any more.
 */
@Service
public class VariantStockService {

    public record Result(BigDecimal previousAvailable, BigDecimal newAvailable, String documentNo) {}

    /** One variant's target stock level, for {@link #setAvailableBatch}. */
    public record Target(UUID variantId, BigDecimal quantity, BigDecimal unitCost) {}

    private final ProductVariantRepository variantRepository;
    private final StockAvailabilityService availabilityService;
    private final StockPostingService postingService;
    private final StockDocumentNoGenerator documentNoGenerator;

    public VariantStockService(ProductVariantRepository variantRepository,
                               StockAvailabilityService availabilityService,
                               StockPostingService postingService,
                               StockDocumentNoGenerator documentNoGenerator) {
        this.variantRepository = variantRepository;
        this.availabilityService = availabilityService;
        this.postingService = postingService;
        this.documentNoGenerator = documentNoGenerator;
    }

    /**
     * @param unitCost cost per unit for added stock; falls back to the variant's standard cost, then zero
     *                 (a missing cost only affects the moving average, never the stock count)
     * @return the before/after figures, with a null document number when nothing needed to change
     */
    @Transactional
    public Result setAvailable(UUID productId, UUID variantId, BigDecimal target, BigDecimal unitCost, StaffUser staff) {
        if (target == null || target.signum() < 0) {
            throw new IllegalArgumentException("Stock quantity cannot be negative");
        }
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new EntityNotFoundException("No variant: " + variantId));
        if (!variant.getProduct().getId().equals(productId)) {
            throw new EntityNotFoundException("No variant " + variantId + " on product " + productId);
        }

        BigDecimal current = availabilityService.available(variantId);
        BigDecimal delta = target.subtract(current);
        if (delta.signum() == 0) {
            return new Result(current, current, null);
        }

        StockDocument document = newDocument(delta, staff);
        document.getLines().add(line(document, variant, 1, delta, unitCost));

        StockDocument posted = postingService.post(document);
        return new Result(current, target, posted.getDocumentNo());
    }

    /**
     * Sets several variants' target stock in one call — the product editor's one-Save flow (Phase 3)
     * calls this once for the whole page instead of once per variant, so editing 10 variants produces
     * at most one GOODS_RECEIPT and one ADJUSTMENT document, not up to 10 of each.
     */
    @Transactional
    public void setAvailableBatch(List<Target> targets, StaffUser staff) {
        if (targets.isEmpty()) {
            return;
        }
        Map<UUID, BigDecimal> current = availabilityService.available(targets.stream().map(Target::variantId).toList());

        record Pending(ProductVariant variant, BigDecimal delta, BigDecimal unitCost) {}
        List<Pending> pendingIncreases = new ArrayList<>();
        List<Pending> pendingDecreases = new ArrayList<>();

        for (Target t : targets) {
            if (t.quantity() == null || t.quantity().signum() < 0) {
                throw new IllegalArgumentException("Stock quantity cannot be negative for variant " + t.variantId());
            }
            ProductVariant variant = variantRepository.findById(t.variantId())
                    .orElseThrow(() -> new EntityNotFoundException("No variant: " + t.variantId()));
            BigDecimal delta = t.quantity().subtract(current.getOrDefault(t.variantId(), BigDecimal.ZERO));
            if (delta.signum() == 0) {
                continue;
            }
            if (delta.signum() > 0) {
                pendingIncreases.add(new Pending(variant, delta, t.unitCost()));
            } else {
                pendingDecreases.add(new Pending(variant, delta, null));
            }
        }

        if (!pendingIncreases.isEmpty()) {
            StockDocument receipt = newDocument(BigDecimal.ONE, staff);
            int lineNo = 1;
            for (Pending p : pendingIncreases) {
                receipt.getLines().add(line(receipt, p.variant(), lineNo++, p.delta(), p.unitCost()));
            }
            postingService.post(receipt);
        }
        if (!pendingDecreases.isEmpty()) {
            StockDocument adjustment = newDocument(BigDecimal.valueOf(-1), staff);
            int lineNo = 1;
            for (Pending p : pendingDecreases) {
                adjustment.getLines().add(line(adjustment, p.variant(), lineNo++, p.delta(), null));
            }
            postingService.post(adjustment);
        }
    }

    private StockDocument newDocument(BigDecimal signedDelta, StaffUser staff) {
        StockDocument document = new StockDocument();
        document.setPostedByUserId(staff.getId());
        if (signedDelta.signum() >= 0) {
            document.setDocumentType(StockDocumentType.GOODS_RECEIPT);
            document.setDocumentNo(documentNoGenerator.next(StockDocumentType.GOODS_RECEIPT));
            document.setNotes("Stock set from the product editor");
        } else {
            document.setDocumentType(StockDocumentType.ADJUSTMENT);
            document.setDocumentNo(documentNoGenerator.next(StockDocumentType.ADJUSTMENT));
            document.setNotes("Stock corrected from the product editor");
        }
        return document;
    }

    private StockDocumentLine line(StockDocument document, ProductVariant variant, int lineNo, BigDecimal delta, BigDecimal unitCost) {
        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(document);
        line.setLineNo(lineNo);
        line.setVariant(variant);
        line.setQty(delta.abs());
        if (delta.signum() > 0) {
            line.setSourceAccount(StockAccount.SUPPLIER);
            line.setTargetAccount(StockAccount.SHOP);
            BigDecimal cost = unitCost != null ? unitCost
                    : variant.getStandardCost() != null ? variant.getStandardCost() : BigDecimal.ZERO;
            line.setUnitCost(cost);
        } else {
            line.setSourceAccount(StockAccount.SHOP);
            line.setTargetAccount(StockAccount.ADJUSTMENT);
        }
        return line;
    }
}
