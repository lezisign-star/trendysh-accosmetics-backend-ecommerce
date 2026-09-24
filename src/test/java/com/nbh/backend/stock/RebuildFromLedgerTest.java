package com.nbh.backend.stock;

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
 * Posts a mixed sequence — two receipts, a sale, and a write-off, all against
 * the single SHOP account (branches/locations were removed 2026-09-24,
 * founder decision — see HANDOFF.md) — then asserts StockBalanceRebuildService's
 * from-ledger recomputation matches the live cache exactly, including the
 * double-entry invariant (SUM(qty_change) per variant across every account = 0).
 */
@SpringBootTest
@ActiveProfiles("test")
class RebuildFromLedgerTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private StockPostingService postingService;
    @Autowired private StockBalanceRebuildService rebuildService;

    @Test
    void rebuildFromLedgerMatchesTheLiveCacheAfterAMixedSequence() {
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

        // Receive 20.
        post(StockDocumentType.GOODS_RECEIPT, variant, StockAccount.SUPPLIER, StockAccount.SHOP, new BigDecimal("20"), new BigDecimal("400.00"));
        // Sell 5 (SHOP -> CUSTOMER, standing in for a real sale since Phase 2 wiring is separate).
        post(StockDocumentType.SALE_ONLINE, variant, StockAccount.SHOP, StockAccount.CUSTOMER, new BigDecimal("5"), null);
        // Write off 2 (shrinkage/damage).
        post(StockDocumentType.WRITE_OFF, variant, StockAccount.SHOP, StockAccount.ADJUSTMENT, new BigDecimal("2"), null);
        // Receive 3 more.
        post(StockDocumentType.GOODS_RECEIPT, variant, StockAccount.SUPPLIER, StockAccount.SHOP, new BigDecimal("3"), new BigDecimal("410.00"));

        // Expected SHOP balance: 20 - 5 - 2 + 3 = 16.
        StockBalanceRebuildService.RebuildReport report = rebuildService.check();

        assertTrue(report.isConsistent(),
                "Rebuild-from-ledger must match the live cache exactly: mismatches=" + report.balanceMismatches()
                        + " doubleEntryViolations=" + report.doubleEntryViolations());
    }

    private void post(StockDocumentType type, ProductVariant variant, StockAccount source, StockAccount target,
                       BigDecimal qty, BigDecimal unitCost) {
        StockDocument doc = new StockDocument();
        doc.setDocumentNo(type.name() + "-" + UUID.randomUUID().toString().substring(0, 8));
        doc.setDocumentType(type);

        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(doc);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceAccount(source);
        line.setTargetAccount(target);
        line.setQty(qty);
        line.setUnitCost(unitCost);
        doc.getLines().add(line);

        postingService.post(doc);
    }
}
