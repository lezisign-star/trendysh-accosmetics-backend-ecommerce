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
import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.PaymentClaimService;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.repository.StockReservationRepository;
import com.nbh.backend.stock.service.StockPostingService;
import com.nbh.backend.stock.service.StockReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Confirming a payment takes its locks payment, then order, then stock. A customer's "I've paid" claim
 * extends the stock holds, so if it took the stock locks first and the payment row afterwards it could
 * sit between two staff/callback confirmations of the same payment, each holding what the other needs
 * next: a deadlock, which Postgres resolves by failing one of the two. This pins the order: while
 * something else holds the payment's row lock (as a confirmation would), a claim must not have touched
 * the stock holds yet.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentClaimLockOrderTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private StockPostingService postingService;
    @Autowired private StockReservationService reservationService;
    @Autowired private StockReservationRepository reservationRepository;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private PaymentReferenceGenerator referenceGenerator;
    @Autowired private PaymentClaimService claimService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aClaimWaitsForThePaymentLockBeforeItTouchesAnyStockHold() throws Exception {
        ProductVariant variant = newVariant();
        receiveOneUnit(variant);
        Instant originalExpiry = Instant.now().plusSeconds(600);
        StockReservation reservation = reservationService.reserve(
                variant, BigDecimal.ONE, StockReservation.ReservationSource.ONLINE_CART,
                "ORDER", UUID.randomUUID(), originalExpiry);

        String orderNo = "CLAIMLOCK-" + UUID.randomUUID().toString().substring(0, 8);
        String reference = referenceGenerator.generate();
        UUID paymentId = newBankOrderWithHold(orderNo, reference, variant, reservation);

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch paymentLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Stands in for confirmSuccess: takes the payment row's lock and keeps it.
            Future<?> confirmation = pool.submit(() -> transactions.executeWithoutResult(status -> {
                paymentRecordRepository.lockById(paymentId).orElseThrow();
                paymentLocked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(paymentLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<PaymentClaim> claim = pool.submit(() -> claimService.submit(
                    orderNo, reference, "QLO" + UUID.randomUUID().toString().substring(0, 7).toUpperCase(),
                    new BigDecimal("500"), null, "Jane"));

            // Give the claim ample time to run as far as it is able to.
            Thread.sleep(800);
            assertThat(claim.isDone()).as("the claim is waiting for the payment's lock").isFalse();

            // Had it taken the stock hold first (the deadlock-prone order) this would fail at once.
            assertThatCode(() -> transactions.executeWithoutResult(status -> jdbc.queryForObject(
                    "select id from stock_reservation where id = ? for update nowait", UUID.class, reservation.getId())))
                    .as("the stock hold is still free while the claim waits")
                    .doesNotThrowAnyException();

            release.countDown();
            confirmation.get(30, TimeUnit.SECONDS);
            assertThat(claim.get(30, TimeUnit.SECONDS)).isNotNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getExpiresAt())
                .as("once it got its turn the claim did extend the hold")
                .isAfter(originalExpiry);
    }

    private UUID newBankOrderWithHold(String orderNo, String reference, ProductVariant variant,
                                      StockReservation reservation) {
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(orderNo);
        order.setGuestEmail("claimlock@example.com");
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
        payment.setProvider("BANK");
        payment.setMethod(PaymentRecord.PaymentMethod.BANK);
        payment.setAmount(new BigDecimal("500.00"));
        payment.setStatus(PaymentRecord.PaymentStatus.INITIATED);
        payment.setPaymentReference(reference);
        payment.setExpiresAt(Instant.now().plusSeconds(600));
        return paymentRecordRepository.save(payment).getId();
    }

    private ProductVariant newVariant() {
        Product product = new Product();
        product.setSlug("claim-lock-" + UUID.randomUUID());
        product.setName("Claim Lock Product");
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("CLAIMLOCK-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setVariantName("Claim Lock Variant");
        variant.setSellingPrice(new BigDecimal("500.00"));
        return variantRepository.save(variant);
    }

    private void receiveOneUnit(ProductVariant variant) {
        StockDocument receipt = new StockDocument();
        receipt.setDocumentNo("GRN-CLAIMLOCK-" + UUID.randomUUID().toString().substring(0, 8));
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
