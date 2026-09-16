package com.nbh.backend.stock;

import com.nbh.backend.branch.entity.Branch;
import com.nbh.backend.branch.entity.LocationType;
import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.branch.repository.StockLocationRepository;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.entity.*;
import com.nbh.backend.stock.service.StockBalanceRebuildService;
import com.nbh.backend.stock.service.StockPostingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The third Phase-1 gate clause the audit (session 3, gap-check §3) found
 * completely uncovered: "a rebuild-from-ledger reproduces balances exactly."
 * Posts a mixed sequence — receipt, sale (write-off standing in for a sale
 * since Phase 2 doesn't exist yet), and a transfer-shaped pair of adjustments
 * across two locations — then asserts StockBalanceRebuildService's from-ledger
 * recomputation matches the live cache exactly, including the double-entry
 * invariant (SUM(qty_change) per variant across every location = 0).
 */
@SpringBootTest
@ActiveProfiles("test")
class RebuildFromLedgerTest {

    @Autowired private BranchRepository branchRepository;
    @Autowired private StockLocationRepository locationRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private StockPostingService postingService;
    @Autowired private StockBalanceRebuildService rebuildService;

    @Test
    void rebuildFromLedgerMatchesTheLiveCacheAfterAMixedSequence() {
        Branch branchA = branchRepository.findByCode("BR-A").orElseThrow();
        Branch branchB = branchRepository.findByCode("BR-B").orElseThrow();
        StockLocation salesFloorA = locationRepository.findByBranchIdAndLocationType(branchA.getId(), LocationType.SALES_FLOOR).orElseThrow();
        StockLocation salesFloorB = locationRepository.findByBranchIdAndLocationType(branchB.getId(), LocationType.SALES_FLOOR).orElseThrow();
        StockLocation supplier = locationRepository.findByCode("SUPPLIER").orElseThrow();
        StockLocation adjustment = locationRepository.findByCode("ADJUSTMENT").orElseThrow();

        Product product = new Product();
        product.setSlug("rebuild-test-" + UUID.randomUUID());
        product.setName("Rebuild Test Product");
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("REBUILD-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setVariantName("Rebuild Test Variant");
        variant.setSellingPrice(new BigDecimal("1000.00"));
        variant = variantRepository.save(variant);

        // Receive 20 at Branch A.
        post(StockDocumentType.GOODS_RECEIPT, variant, supplier, salesFloorA, new BigDecimal("20"), new BigDecimal("400.00"));
        // Sell (write off, standing in for a sale) 5 at Branch A.
        post(StockDocumentType.WRITE_OFF, variant, salesFloorA, adjustment, new BigDecimal("5"), null);
        // Transfer 8 from Branch A to Branch B directly (one line, both sides real internal locations).
        post(StockDocumentType.TRANSFER, variant, salesFloorA, salesFloorB, new BigDecimal("8"), null);
        // Receive 3 more directly at Branch B (e.g. a separate local supplier).
        post(StockDocumentType.GOODS_RECEIPT, variant, supplier, salesFloorB, new BigDecimal("3"), new BigDecimal("410.00"));

        // Expected: Branch A = 20 - 5 - 8 = 7; Branch B = 8 + 3 = 11.
        StockBalanceRebuildService.RebuildReport report = rebuildService.check();

        assertTrue(report.isConsistent(),
                "Rebuild-from-ledger must match the live cache exactly: mismatches=" + report.balanceMismatches()
                        + " doubleEntryViolations=" + report.doubleEntryViolations());
    }

    private void post(StockDocumentType type, ProductVariant variant, StockLocation source, StockLocation target,
                       BigDecimal qty, BigDecimal unitCost) {
        StockDocument doc = new StockDocument();
        doc.setDocumentNo(type.name() + "-" + UUID.randomUUID().toString().substring(0, 8));
        doc.setDocumentType(type);
        doc.setBranch(source.getBranch() != null ? source.getBranch() : target.getBranch());

        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(doc);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceLocation(source);
        line.setTargetLocation(target);
        line.setQty(qty);
        line.setUnitCost(unitCost);
        doc.getLines().add(line);

        postingService.post(doc);
    }
}
