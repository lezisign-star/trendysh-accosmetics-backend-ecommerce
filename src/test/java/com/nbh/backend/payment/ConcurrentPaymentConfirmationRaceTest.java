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
import org.junit.jupiter.api.RepeatedTest;
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

    // Repeated so that BOTH interleavings (confirmation first, failure first) are very likely to be seen in a run.
    @RepeatedTest(12)
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

        // What the §4 C2 fix relies on: each operation locks the payment row and then re-reads its FRESH status, so
        // the two are strictly one after the other and the later one sees what the earlier committed. Before the
        // fix (an unlocked findById) both saw the pre-race snapshot and whichever committed last silently
        // overwrote the other's payment/order rows while the reservation still reflected the FIRST one's effect.
        //
        // Since 2026-10-07 a payment marked failed can be confirmed again (the way back from a mistaken "failed"
        // once the customer shows the M-PESA message), so a confirmation that runs AFTER the failure no longer
        // refuses itself: it brings the order back. A confirmation can never be lost to a failure, then:
        //   - confirm first: it applies, and the failure that follows is refused (a succeeded payment cannot be failed);
        //   - failure first: it applies, and the confirmation that follows revives the order and holds the stock again.
        // Either way the order ends PAID with exactly one live hold. Both outcomes are asserted below.
        assertTrue(confirmApplied.get(), "A confirmation must never be lost to a concurrent failure");
        assertFalse(confirmRejected.get(), "A confirmation is refused neither before nor after a failure");
        assertNotEquals(failApplied.get(), failRejected.get(),
                "The failure either ran first (applied, then revived) or came second and was refused");

        PaymentRecord finalPayment = paymentRecordRepository.findById(paymentId).orElseThrow();
        CustomerOrder finalOrder = orderRepository.findById(orderId).orElseThrow();
        var finalBalance = balanceRepository.findByVariantIdAndAccount(variant.getId(), StockAccount.SHOP)
                .orElseThrow();
        // The hold may be a NEW row (a revived order re-takes it), so look at every hold made for this order.
        var holds = reservationRepository.findAll().stream()
                .filter(r -> reservation.getReferenceId().equals(r.getReferenceId()))
                .toList();
        var live = holds.stream().filter(r -> r.getStatus() == StockReservation.ReservationStatus.ACTIVE).toList();

        assertEquals(PaymentRecord.PaymentStatus.SUCCESS, finalPayment.getStatus(),
                "Payment must land on the confirmation, not in between");
        assertEquals(OrderStatus.PAID, finalOrder.getStatus());
        assertEquals(1, live.size(), "A PAID order must hold its stock exactly once, whichever ran first");
        assertEquals(StockReservation.ReservationSource.ONLINE_ORDER, live.get(0).getSource());
        assertEquals(null, live.get(0).getExpiresAt(), "A paid order's hold is permanent");
        assertEquals(0, finalBalance.getReservedQty().compareTo(BigDecimal.ONE),
                "Exactly one unit is reserved, never zero (released under a paid order) and never two");
        if (failApplied.get()) {
            assertEquals(2, holds.size(), "The failure released the first hold and the confirmation took a new one");
        }
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
