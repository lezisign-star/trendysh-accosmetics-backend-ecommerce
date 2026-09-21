package com.nbh.backend.stock.service;

import com.nbh.backend.branch.entity.Branch;
import com.nbh.backend.branch.entity.LocationType;
import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.branch.repository.StockLocationRepository;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.service.StaffBranchAccessService;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * "Set this variant's stock to N" for the product editor — the everyday case of a shop owner typing a
 * quantity, without knowing about receipts or adjustments. The difference from what is sellable now
 * is posted through {@link StockPostingService} (the single write path), so the ledger stays a
 * complete, balanced audit trail:
 * <ul>
 *   <li>more stock than before: a GOODS_RECEIPT from SUPPLIER into the default online-fulfilment
 *       branch's sales floor (the shelf the storefront cart checks);</li>
 *   <li>less stock: an ADJUSTMENT write-off out of that same shelf.</li>
 * </ul>
 * The target is the SELLABLE quantity (on hand minus reserved, all physical locations), i.e. exactly the
 * number the editor shows, so a pending checkout reservation never causes a wrong correction.
 */
@Service
public class VariantStockService {

    public record Result(BigDecimal previousAvailable, BigDecimal newAvailable, String documentNo) {}

    private final ProductVariantRepository variantRepository;
    private final StockBalanceRepository balanceRepository;
    private final BranchRepository branchRepository;
    private final StockLocationRepository locationRepository;
    private final StockPostingService postingService;
    private final StockDocumentNoGenerator documentNoGenerator;
    private final StaffBranchAccessService branchAccessService;

    public VariantStockService(ProductVariantRepository variantRepository,
                               StockBalanceRepository balanceRepository,
                               BranchRepository branchRepository,
                               StockLocationRepository locationRepository,
                               StockPostingService postingService,
                               StockDocumentNoGenerator documentNoGenerator,
                               StaffBranchAccessService branchAccessService) {
        this.variantRepository = variantRepository;
        this.balanceRepository = balanceRepository;
        this.branchRepository = branchRepository;
        this.locationRepository = locationRepository;
        this.postingService = postingService;
        this.documentNoGenerator = documentNoGenerator;
        this.branchAccessService = branchAccessService;
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

        Branch branch = branchRepository.findByIsDefaultOnlineFulfilmentTrue()
                .orElseThrow(() -> new ConfigurationException("No branch is configured as the default online-fulfilment branch"));
        branchAccessService.assertCanAccessBranch(staff, branch.getId());
        StockLocation shelf = locationRepository.findByBranchIdAndLocationType(branch.getId(), LocationType.SALES_FLOOR)
                .orElseThrow(() -> new ConfigurationException("The default branch has no sales-floor location"));

        BigDecimal current = balanceRepository.availabilityForVariants(java.util.List.of(variantId)).stream()
                .findFirst().map(StockBalanceRepository.VariantAvailability::getAvailable).orElse(BigDecimal.ZERO);
        BigDecimal delta = target.subtract(current);
        if (delta.signum() == 0) {
            return new Result(current, current, null);
        }

        StockDocument document = new StockDocument();
        document.setBranch(branch);
        document.setPostedByUserId(staff.getId());
        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(document);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setQty(delta.abs());

        if (delta.signum() > 0) {
            document.setDocumentType(StockDocumentType.GOODS_RECEIPT);
            document.setDocumentNo(documentNoGenerator.next(StockDocumentType.GOODS_RECEIPT));
            document.setNotes("Stock set from the product editor");
            line.setSourceLocation(virtual("SUPPLIER"));
            line.setTargetLocation(shelf);
            BigDecimal cost = unitCost != null ? unitCost
                    : variant.getStandardCost() != null ? variant.getStandardCost() : BigDecimal.ZERO;
            line.setUnitCost(cost);
        } else {
            document.setDocumentType(StockDocumentType.ADJUSTMENT);
            document.setDocumentNo(documentNoGenerator.next(StockDocumentType.ADJUSTMENT));
            document.setNotes("Stock corrected from the product editor");
            line.setSourceLocation(shelf);
            line.setTargetLocation(virtual("ADJUSTMENT"));
        }
        document.getLines().add(line);

        StockDocument posted = postingService.post(document);
        return new Result(current, target, posted.getDocumentNo());
    }

    private StockLocation virtual(String code) {
        return locationRepository.findByCode(code)
                .orElseThrow(() -> new ConfigurationException("Missing virtual stock location " + code));
    }
}
