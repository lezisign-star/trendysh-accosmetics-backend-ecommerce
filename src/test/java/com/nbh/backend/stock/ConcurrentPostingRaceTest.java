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
import com.nbh.backend.stock.exception.InsufficientStockException;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.service.StockBalanceRebuildService;
import com.nbh.backend.stock.service.StockPostingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audit finding (session 3, gap-check §3): the architecture doc's Phase-1 gate
 * requires "N parallel threads racing for the last unit... asserting exactly
 * one wins, on_hand_qty never goes negative" for StockPostingService.post()
 * itself — the reservation race test only exercises StockReservationService
 * (which never touches on_hand_qty). This is the test that was missing.
 *
 * Also covers the first-ever-receipt race for a never-before-stocked
 * (variant, location) pair (audit finding F5): N concurrent GOODS_RECEIPTs
 * for the same new pair must all succeed and sum correctly, not 500 on the
 * first-balance-creation race.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentPostingRaceTest {

    @Autowired private BranchRepository branchRepository;
    @Autowired private StockLocationRepository locationRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private StockPostingService postingService;
    @Autowired private StockBalanceRepository balanceRepository;
    @Autowired private StockBalanceRebuildService rebuildService;

    private StockLocation salesFloor;
    private StockLocation supplier;
    private StockLocation adjustment;
    private Branch branchA;

    @BeforeEach
    void setUp() {
        // Resolved eagerly and reused directly (never via salesFloor.getBranch(),
        // a lazy proxy) — concurrently touching the SAME lazy proxy from many
        // worker threads' separate Hibernate sessions throws "illegally attempted
        // to associate proxy... with two open Sessions". A test-only concern, not
        // a StockPostingService bug.
        branchA = branchRepository.findByCode("BR-A").orElseThrow();
        salesFloor = locationRepository.findByBranchIdAndLocationType(branchA.getId(), LocationType.SALES_FLOOR)
                .orElseThrow();
        supplier = locationRepository.findByCode("SUPPLIER").orElseThrow();
        adjustment = locationRepository.findByCode("ADJUSTMENT").orElseThrow();
    }

    private ProductVariant newVariant(String skuPrefix) {
        Product product = new Product();
        product.setSlug("posting-race-" + UUID.randomUUID());
        product.setName("Posting Race Product");
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku(skuPrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setVariantName("Posting Race Variant");
        variant.setSellingPrice(new BigDecimal("500.00"));
        return variantRepository.save(variant);
    }

    private StockDocument draft(StockDocumentType type, String noPrefix, ProductVariant variant,
                                 StockLocation source, StockLocation target, BigDecimal qty, BigDecimal unitCost) {
        StockDocument doc = new StockDocument();
        doc.setDocumentNo(noPrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        doc.setDocumentType(type);
        doc.setBranch(branchA);

        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(doc);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceLocation(source);
        line.setTargetLocation(target);
        line.setQty(qty);
        line.setUnitCost(unitCost);
        doc.getLines().add(line);
        return doc;
    }

    @Test
    void exactlyOnePostingWinsTheLastUnitAndOnHandNeverGoesNegative() throws Exception {
        ProductVariant variant = newVariant("PRACE");
        postingService.post(draft(StockDocumentType.GOODS_RECEIPT, "GRN", variant, supplier, salesFloor,
                BigDecimal.ONE, new BigDecimal("300.00")));

        int attempts = 12;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    // Each thread writes off the last unit out of the sales floor into
                    // the ADJUSTMENT virtual location (keeping the movement properly
                    // double-entry) — enough to exercise the same on_hand-goes-negative
                    // guard a SALE_POS document would hit.
                    postingService.post(draft(StockDocumentType.WRITE_OFF, "WO", variant, salesFloor, adjustment,
                            BigDecimal.ONE, null));
                    succeeded.incrementAndGet();
                } catch (InsufficientStockException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, succeeded.get(), "Exactly one concurrent posting for the last unit must win");
        assertEquals(attempts - 1, rejected.get(), "Every other concurrent posting must be rejected");

        var balance = balanceRepository.findByVariantIdAndLocationId(variant.getId(), salesFloor.getId()).orElseThrow();
        assertEquals(0, balance.getOnHandQty().compareTo(BigDecimal.ZERO), "on_hand must never go negative");
    }

    @Test
    void concurrentFirstReceiptsForANeverBeforeStockedPairAllSucceed() throws Exception {
        ProductVariant variant = newVariant("FIRSTRCV");

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    postingService.post(draft(StockDocumentType.GOODS_RECEIPT, "GRN", variant, supplier, salesFloor,
                            BigDecimal.ONE, new BigDecimal("300.00")));
                    succeeded.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS); // any unhandled exception (e.g. a raw ConstraintViolationException) fails here
        }
        pool.shutdown();

        assertEquals(attempts, succeeded.get(), "Every concurrent first-receipt must succeed, none should 500");

        var balance = balanceRepository.findByVariantIdAndLocationId(variant.getId(), salesFloor.getId()).orElseThrow();
        assertEquals(0, balance.getOnHandQty().compareTo(BigDecimal.valueOf(attempts)),
                "No receipt may be lost to the first-balance-creation race");

        assertTrue(rebuildService.check().isConsistent(), "Ledger-derived balance must match the cache after the race");
    }
}
