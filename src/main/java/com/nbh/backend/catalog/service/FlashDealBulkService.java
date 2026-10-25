package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.BulkProductActionResult;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Starts or ends flash deals on whole products at once, from the admin product list or a single
 * product's editor. Each variant still goes through {@link VariantDealService#setDeal} so the
 * "deal price below selling price, deadline in the future" rules live in one place; this only turns
 * "20% off until Friday" into a deal price per variant, rounded to whole shillings.
 *
 * <p>Packages are skipped on purpose: a package already sells at its own package price, and a second
 * discount stacked on it would quietly undercut the margin the package was priced to keep.
 */
@Service
public class FlashDealBulkService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final VariantDealService dealService;
    private final Clock clock;

    public FlashDealBulkService(ProductRepository productRepository, ProductVariantRepository variantRepository,
                                 VariantDealService dealService, Clock clock) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.dealService = dealService;
        this.clock = clock;
    }

    @Transactional
    public BulkProductActionResult start(List<UUID> productIds, int percentOff, Instant endsAt) {
        if (!endsAt.isAfter(Instant.now(clock))) {
            throw new IllegalArgumentException("A flash deal must end in the future");
        }
        int affectedProducts = 0;
        int affectedVariants = 0;
        int skippedPackages = 0;
        int skippedUnpriced = 0;
        int skippedTooCheap = 0;

        for (Product product : productRepository.findAllById(productIds.stream().distinct().toList())) {
            if (product.isDeleted()) continue;
            int applied = 0;
            for (ProductVariant variant : variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(product.getId())) {
                if (variant.isPackage()) {
                    skippedPackages++;
                    continue;
                }
                if (!variant.isActive() || variant.getSellingPrice() == null) {
                    skippedUnpriced++;
                    continue;
                }
                BigDecimal price = variant.getSellingPrice();
                BigDecimal dealPrice = price.multiply(BigDecimal.valueOf(100L - percentOff))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                if (dealPrice.signum() <= 0 || dealPrice.compareTo(price) >= 0) {
                    skippedTooCheap++;
                    continue;
                }
                dealService.setDeal(product.getId(), variant.getId(), dealPrice, endsAt);
                applied++;
            }
            if (applied > 0) {
                affectedProducts++;
                affectedVariants += applied;
            }
        }

        List<String> warnings = new ArrayList<>();
        if (skippedPackages > 0) warnings.add(skippedPackages + " package(s) skipped — packages keep their own package price");
        if (skippedUnpriced > 0) warnings.add(skippedUnpriced + " variant(s) skipped — no price or not active");
        if (skippedTooCheap > 0) warnings.add(skippedTooCheap + " variant(s) skipped — too cheap for a whole-shilling discount");
        return new BulkProductActionResult(affectedProducts, affectedVariants, warnings);
    }

    @Transactional
    public BulkProductActionResult end(List<UUID> productIds) {
        int affectedProducts = 0;
        int affectedVariants = 0;
        for (Product product : productRepository.findAllById(productIds.stream().distinct().toList())) {
            int cleared = 0;
            for (ProductVariant variant : variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(product.getId())) {
                if (variant.getDealPrice() != null) {
                    dealService.clearDeal(product.getId(), variant.getId());
                    cleared++;
                }
            }
            if (cleared > 0) {
                affectedProducts++;
                affectedVariants += cleared;
            }
        }
        return new BulkProductActionResult(affectedProducts, affectedVariants, List.of());
    }
}
