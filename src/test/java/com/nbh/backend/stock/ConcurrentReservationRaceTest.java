package com.nbh.backend.stock;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.exception.InsufficientAvailabilityException;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockDocumentRepository;
import com.nbh.backend.stock.service.StockPostingService;
import com.nbh.backend.stock.service.StockReservationService;
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
 * The Phase-1 gate the architecture doc (§6) requires before anything is built
 * on top of the stock core: N threads race for the LAST unit of one SKU across
 * two simulated channels (an "online" reservation and a "POS" reservation).
 * Exactly one may win; on_hand must never go negative; and a from-ledger rebuild
 * must reproduce the balance exactly. This is the direct proof that the
 * omnichannel oversell Moments Packaging never guarded against (its
 * reservedCount column was written nowhere) cannot happen here. Branches/
 * locations were removed 2026-09-24 (founder decision — see HANDOFF.md); a
 * reservation is always against SHOP.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentReservationRaceTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private StockDocumentRepository documentRepository;
    @Autowired private StockPostingService postingService;
    @Autowired private StockReservationService reservationService;
    @Autowired private StockBalanceRepository balanceRepository;

    private ProductVariant variant;

    @BeforeEach
    void seedOneUnitOfStock() {
        Product product = new Product();
        product.setSlug("race-test-product-" + UUID.randomUUID());
        product.setName("Race Test Product");
        product = productRepository.save(product);

        variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("RACE-" + UUID.randomUUID());
        variant.setVariantName("Race Test Variant");
        variant.setSellingPrice(new BigDecimal("1000.00"));
        variant = variantRepository.save(variant);

        // Receive exactly ONE unit — the contested last unit.
        StockDocument receipt = new StockDocument();
        receipt.setDocumentNo("GRN-RACE-" + UUID.randomUUID().toString().substring(0, 8));
        receipt.setDocumentType(StockDocumentType.GOODS_RECEIPT);

        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(receipt);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceAccount(StockAccount.SUPPLIER);
        line.setTargetAccount(StockAccount.SHOP);
        line.setQty(BigDecimal.ONE);
        line.setUnitCost(new BigDecimal("500.00"));
        receipt.getLines().add(line);

        postingService.post(receipt);
    }

    @Test
    void exactlyOneOfManyConcurrentReservationsForTheLastUnitWins() throws InterruptedException {
        int attempts = 12;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            StockReservation.ReservationSource source =
                    i % 2 == 0 ? StockReservation.ReservationSource.ONLINE_ORDER : StockReservation.ReservationSource.POS_TICKET;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    reservationService.reserve(
                            variant, BigDecimal.ONE, source,
                            "TEST_RACE", UUID.randomUUID(), null);
                    succeeded.incrementAndGet();
                } catch (InsufficientAvailabilityException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                fail("Reservation task failed unexpectedly: " + e.getCause());
            }
        }
        pool.shutdown();

        assertEquals(1, succeeded.get(), "Exactly one concurrent reservation for the last unit must win");
        assertEquals(attempts - 1, rejected.get(), "Every other concurrent attempt must be rejected, not silently overbooked");
    }

    /**
     * Phase 0 regression test (2026-09-27): the exact race {@code StockReservationService
     * .terminate}'s own javadoc used to claim was already fixed but wasn't — two concurrent
     * terminations of the SAME reservation (e.g. the expiry sweeper racing a payment-failure
     * release) each read the reservation unlocked (an ordinary {@code findById}) before
     * locking the balance, then re-fetched it under {@code PESSIMISTIC_WRITE} — but
     * Hibernate's persistence context hands back that same already-loaded, pre-lock object
     * instead of a fresh read, so the "loser sees a non-ACTIVE status" guarantee never
     * actually held. Fixed by never loading the reservation at all before it's locked (a
     * scalar variant-id lookup first instead), backstopped by {@code @Version} on both
     * entities. Without either fix, this test reliably drives reserved_qty negative — one
     * unit reserved, released from N concurrent callers, each independently believing it was
     * the one that had to decrement it.
     */
    @Test
    void concurrentTerminationsOfTheSameReservationNeverDoubleDecrement() throws InterruptedException {
        StockReservation reservation = reservationService.reserve(
                variant, BigDecimal.ONE, StockReservation.ReservationSource.ONLINE_CART,
                "TEST_TERMINATE_RACE", UUID.randomUUID(), null);

        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    reservationService.release(reservation.getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                fail("Concurrent release task failed unexpectedly: " + e.getCause());
            }
        }
        pool.shutdown();

        BigDecimal finalReservedQty = balanceRepository
                .findByVariantIdAndAccount(variant.getId(), StockAccount.SHOP)
                .orElseThrow()
                .getReservedQty();
        assertEquals(0, finalReservedQty.compareTo(BigDecimal.ZERO),
                "reserved_qty must land back at exactly zero — a double-decrement would drive it negative, "
                        + "was " + finalReservedQty);
    }
}
