package com.nbh.backend.stock;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.entity.StockLedgerEntry;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockDocumentRepository;
import com.nbh.backend.stock.repository.StockLedgerEntryRepository;
import com.nbh.backend.stock.service.StockPostingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 (2026-09-27): {@code StockPostingService.reverse} against a real Postgres instance —
 * proves the exact negation posts correctly, at the original entries' own cost, tagged as a
 * reversal, with the original document marked cancelled-by it; and that the type/already-
 * reversed guards actually reject what they're supposed to.
 */
@SpringBootTest
@ActiveProfiles("test")
class StockDocumentReversalIntegrationTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private StockPostingService postingService;
    @Autowired private StockBalanceRepository balanceRepository;
    @Autowired private StockLedgerEntryRepository ledgerRepository;
    @Autowired private StockDocumentRepository documentRepository;

    private ProductVariant variant;

    @BeforeEach
    void seedVariant() {
        Product product = new Product();
        product.setSlug("reversal-test-" + UUID.randomUUID());
        product.setName("Reversal Test Product");
        product = productRepository.save(product);

        variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("REVERSAL-" + UUID.randomUUID());
        variant.setVariantName("Test Variant");
        variant.setSellingPrice(new BigDecimal("1000.00"));
        variant = variantRepository.save(variant);
    }

    private StockDocument receive(BigDecimal qty, BigDecimal unitCost) {
        StockDocument receipt = new StockDocument();
        receipt.setDocumentNo("GRN-REV-" + UUID.randomUUID().toString().substring(0, 8));
        receipt.setDocumentType(StockDocumentType.GOODS_RECEIPT);

        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(receipt);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceAccount(StockAccount.SUPPLIER);
        line.setTargetAccount(StockAccount.SHOP);
        line.setQty(qty);
        line.setUnitCost(unitCost);
        receipt.getLines().add(line);

        return postingService.post(receipt);
    }

    @Test
    void reversingAGoodsReceiptUndoesItExactlyAtTheOriginalCost() {
        StockDocument original = receive(new BigDecimal("10"), new BigDecimal("500.00"));

        // A second, differently-priced receipt lands in between — proves the reversal uses the
        // ORIGINAL entry's cost, not whatever the average has drifted to by reversal time.
        receive(new BigDecimal("10"), new BigDecimal("700.00"));

        StockDocument reversal = postingService.reverse(original.getId(), "test correction", UUID.randomUUID());

        assertEquals(StockDocumentType.ADJUSTMENT, reversal.getDocumentType());
        assertEquals(StockPostingService.REVERSAL_REFERENCE_TYPE, reversal.getReferenceType());
        assertEquals(original.getId(), reversal.getReferenceId());
        assertEquals(StockDocument.StockDocumentStatus.POSTED, reversal.getStatus());

        BigDecimal onHand = balanceRepository.findByVariantIdAndAccount(variant.getId(), StockAccount.SHOP)
                .orElseThrow().getOnHandQty();
        assertEquals(0, onHand.compareTo(new BigDecimal("10")),
                "10 (first receipt) + 10 (second) - 10 (reversal of the first) = 10");

        List<StockLedgerEntry> reversalEntries = ledgerRepository.findByDocumentIdOrderByIdAsc(reversal.getId());
        assertEquals(2, reversalEntries.size());
        for (StockLedgerEntry entry : reversalEntries) {
            assertTrue(entry.isReversal(), "every entry on a reversal document must be tagged as one");
            assertNotNull(entry.getReversesEntry(), "must point back to the original entry it undoes");
        }
        // Only the physical (SHOP) leg's cost is the invariant that matters — the virtual
        // SUPPLIER leg's original entry was valued at SUPPLIER's own then-current average (0,
        // since nothing had been received into SUPPLIER yet), which is correct bookkeeping, not
        // a bug. The original receipt's SHOP leg was valued at 500 — the reversal's SHOP leg
        // must use that, not the 700 the average has since moved toward.
        StockLedgerEntry shopReversalEntry = reversalEntries.stream()
                .filter(e -> e.getAccount() == StockAccount.SHOP).findFirst().orElseThrow();
        assertEquals(0, shopReversalEntry.getUnitCost().compareTo(new BigDecimal("500.0000")),
                "the SHOP leg of the reversal must be valued at the ORIGINAL entry's cost (500), not a fresh current average");

        StockDocument reloadedOriginal = documentRepository.findById(original.getId()).orElseThrow();
        assertNotNull(reloadedOriginal.getCancelledByDocument(), "the original must now point at what reversed it");
        assertEquals(reversal.getId(), reloadedOriginal.getCancelledByDocument().getId());
    }

    @Test
    void reversingTheSameDocumentTwiceIsRejected() {
        StockDocument original = receive(BigDecimal.TEN, new BigDecimal("100.00"));
        postingService.reverse(original.getId(), null, UUID.randomUUID());

        assertThrows(UnprocessableException.class,
                () -> postingService.reverse(original.getId(), null, UUID.randomUUID()));
    }

    /**
     * Two staff pressing "reverse" on the same document at the same moment: each would see "not yet
     * reversed", each post the undoing movement, and the stock would go out twice. The document is
     * locked while it is reversed, so exactly one wins and the other is told it was already reversed.
     */
    @Test
    void reversingTheSameDocumentAtTheSameMomentTakesTheStockOutOnlyOnce() throws Exception {
        StockDocument original = receive(new BigDecimal("10"), new BigDecimal("100.00"));
        receive(new BigDecimal("10"), new BigDecimal("100.00")); // enough on hand that a second reversal WOULD succeed

        int attempts = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(attempts);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    postingService.reverse(original.getId(), null, UUID.randomUUID());
                    return true;
                } catch (UnprocessableException alreadyReversed) {
                    return false;
                }
            }));
        }
        go.countDown();
        int succeeded = 0;
        for (java.util.concurrent.Future<Boolean> result : results) {
            if (result.get()) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertEquals(1, succeeded, "only one reversal may post");
        BigDecimal onHand = balanceRepository.findByVariantIdAndAccount(variant.getId(), StockAccount.SHOP)
                .orElseThrow().getOnHandQty();
        assertEquals(0, onHand.compareTo(new BigDecimal("10")), "20 received - 10 reversed once = 10, not 0 or less");
    }

    @Test
    void reversingASaleIsRejectedSalesMustGoThroughAReturn() {
        receive(BigDecimal.TEN, new BigDecimal("100.00"));

        StockDocument sale = new StockDocument();
        sale.setDocumentNo("SALE-REV-" + UUID.randomUUID().toString().substring(0, 8));
        sale.setDocumentType(StockDocumentType.SALE_ONLINE);
        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(sale);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceAccount(StockAccount.SHOP);
        line.setTargetAccount(StockAccount.CUSTOMER);
        line.setQty(BigDecimal.ONE);
        sale.getLines().add(line);
        StockDocument postedSale = postingService.post(sale);

        assertThrows(UnprocessableException.class,
                () -> postingService.reverse(postedSale.getId(), null, UUID.randomUUID()));
    }
}
