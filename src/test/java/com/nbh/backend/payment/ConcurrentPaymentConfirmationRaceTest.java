package com.nbh.backend.payment;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.PaymentService;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockReservationRepository;
import com.nbh.backend.stock.service.StockPostingService;
import com.nbh.backend.stock.service.StockReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4 C2: {@code PaymentService.confirmSuccess}/{@code markFailed} used an
 * unlocked {@code findById}, so a Daraja callback retry racing a staff manual
 * confirmation (or a manual "fail" racing an arriving success callback) could
 * both read {@code INITIATED}, both pass their status guard, and both write —
 * e.g. {@code markFailed} releasing the stock of an order {@code confirmSuccess}
 * had already marked PAID in the same instant. Same "cannot exist in a mocked
 * test" property as {@code ConcurrentPostingRaceTest}/{@code
 * ConcurrentReservationRaceTest}: this needs a real Postgres row lock actually
 * serialising two real transactions, not a stub.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentPaymentConfirmationRaceTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private StockPostingService postingService;
    @Autowired private StockReservationService reservationService;
    @Autowired private StockReservationRepository reservationRepository;
    @Autowired private StockBalanceRepository balanceRepository;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private PaymentService paymentService;

    @Test
    void concurrentConfirmSuccessAndMarkFailedOnTheSamePaymentNeverCorruptStockOrOrderState() throws Exception {
        ProductVariant variant = newVariant();
        receiveOneUnit(variant);

        StockReservation reservation = reservationService.reserve(
                variant, BigDecimal.ONE, StockReservation.ReservationSource.ONLINE_CART,
                "ORDER", UUID.randomUUID(), null);

        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("RACE-" + UUID.randomUUID().toString().substring(0, 8));
        order.setGuestEmail("racer@example.com");
        order.setGuestPhone("254700000000");
        order.setFulfilmentType(FulfilmentType.PICKUP);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setSubtotal(new BigDecimal("500.00"));
        order.setVatTotal(BigDecimal.ZERO);
        order.setGrandTotal(new BigDecimal("500.00"));

        CustomerOrderItem item = new CustomerOrderItem();
        item.setOrder(order);
        item.setVariant(variant);
        item.setQty(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("500.00"));
        item.setVatRate(BigDecimal.ZERO);
        item.setLineTotal(new BigDecimal("500.00"));
        item.setReservation(reservation);
        order.getItems().add(item);
        order = orderRepository.save(order);

        PaymentRecord payment = new PaymentRecord();
        payment.setOrder(order);
        payment.setProvider("MPESA");
        payment.setMethod(PaymentRecord.PaymentMethod.MPESA);
        payment.setAmount(new BigDecimal("500.00"));
        payment.setStatus(PaymentRecord.PaymentStatus.INITIATED);
        payment = paymentRecordRepository.save(payment);
        UUID paymentId = payment.getId();
        UUID orderId = order.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean confirmApplied = new AtomicBoolean(false);
        AtomicBoolean confirmRejected = new AtomicBoolean(false);
        AtomicBoolean failApplied = new AtomicBoolean(false);
        AtomicBoolean failRejected = new AtomicBoolean(false);

        String providerReference = "MPESA-RECEIPT-" + UUID.randomUUID();
        Future<?> confirmFuture = pool.submit(() -> {
            ready.countDown();
            await(go);
            try {
                paymentService.confirmSuccess(paymentId, providerReference, "{}");
                confirmApplied.set(true);
            } catch (IllegalStateException e) {
                confirmRejected.set(true);
            }
        });
        Future<?> failFuture = pool.submit(() -> {
            ready.countDown();
            await(go);
            try {
                paymentService.markFailed(paymentId, "Simulated concurrent failure", "{}");
                failApplied.set(true);
            } catch (IllegalStateException e) {
                failRejected.set(true);
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        confirmFuture.get(30, TimeUnit.SECONDS);
        failFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // The mechanism the §4 C2 fix actually relies on: whichever operation's
        // transaction commits second must, after acquiring the now-uncontended
        // payment-row lock, re-read the FRESH (already-terminal) status and
        // reject itself via the existing guard — never silently overwrite the
        // winner's committed result. Before the fix (an unlocked findById) BOTH
        // operations would see the pre-race snapshot, both would pass their
        // guard, and whichever committed last would silently overwrite the
        // other's already-committed payment/order rows while the reservation
        // table (independently lock-protected since audit finding F4) still
        // reflected the FIRST operation's effect — the exact cross-table
        // corruption this test's final assertions check for below. Asserting
        // "exactly one applied, the other was rejected" is what actually pins
        // the fix; asserting only on final state (as a first draft of this test
        // did) passes even on the old, buggy code, because a last-write-wins
        // overwrite still leaves payment/order self-consistent with each other.
        assertNotEquals(confirmApplied.get(), failApplied.get(),
                "Exactly one of confirmSuccess/markFailed must actually apply its write");
        assertNotEquals(confirmRejected.get(), failRejected.get(),
                "Exactly one of confirmSuccess/markFailed must be rejected by the other's already-committed status");
        assertTrue(confirmApplied.get() || failApplied.get(), "One of the two operations must have succeeded");

        PaymentRecord finalPayment = paymentRecordRepository.findById(paymentId).orElseThrow();
        CustomerOrder finalOrder = orderRepository.findById(orderId).orElseThrow();
        StockReservation finalReservation = reservationRepository.findById(reservation.getId()).orElseThrow();
        var finalBalance = balanceRepository.findByVariantIdAndAccount(variant.getId(), StockAccount.SHOP)
                .orElseThrow();

        assertTrue(
                finalPayment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS
                        || finalPayment.getStatus() == PaymentRecord.PaymentStatus.FAILED,
                "Payment must land on exactly one terminal status, not something in between");
        assertEquals(confirmApplied.get(), finalPayment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS,
                "Final payment status must match whichever operation actually applied");

        if (finalPayment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
            assertEquals(OrderStatus.PAID, finalOrder.getStatus());
            assertEquals(StockReservation.ReservationStatus.ACTIVE, finalReservation.getStatus(),
                    "A PAID order's stock hold must still be held, not released by the losing concurrent markFailed");
            assertEquals(StockReservation.ReservationSource.ONLINE_ORDER, finalReservation.getSource());
            assertEquals(0, finalBalance.getReservedQty().compareTo(BigDecimal.ONE));
        } else {
            assertEquals(OrderStatus.PAYMENT_FAILED, finalOrder.getStatus());
            assertEquals(StockReservation.ReservationStatus.RELEASED, finalReservation.getStatus(),
                    "A failed order's stock hold must be released, not left permanently ACTIVE by the losing confirmSuccess");
            assertEquals(0, finalBalance.getReservedQty().compareTo(BigDecimal.ZERO));
        }

        // The exact corruption C2 describes: the order says PAID but the stock
        // was given back anyway (or vice versa) — must never be observable.
        boolean corrupted =
                (finalOrder.getStatus() == OrderStatus.PAID
                        && finalReservation.getStatus() != StockReservation.ReservationStatus.ACTIVE)
                || (finalOrder.getStatus() == OrderStatus.PAYMENT_FAILED
                        && finalReservation.getStatus() == StockReservation.ReservationStatus.ACTIVE);
        assertFalse(corrupted, "Order status and reservation state must never disagree");
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ProductVariant newVariant() {
        Product product = new Product();
        product.setSlug("payment-race-" + UUID.randomUUID());
        product.setName("Payment Race Product");
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("PAYRACE-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setVariantName("Payment Race Variant");
        variant.setSellingPrice(new BigDecimal("500.00"));
        return variantRepository.save(variant);
    }

    private void receiveOneUnit(ProductVariant variant) {
        StockDocument receipt = new StockDocument();
        receipt.setDocumentNo("GRN-PAYRACE-" + UUID.randomUUID().toString().substring(0, 8));
        receipt.setDocumentType(StockDocumentType.GOODS_RECEIPT);

        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(receipt);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceAccount(StockAccount.SUPPLIER);
        line.setTargetAccount(StockAccount.SHOP);
        line.setQty(BigDecimal.ONE);
        line.setUnitCost(new BigDecimal("300.00"));
        receipt.getLines().add(line);

        postingService.post(receipt);
    }
}
