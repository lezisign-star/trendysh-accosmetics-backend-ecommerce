package com.nbh.backend.payment;

import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.BankCreditService;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * A payment reference in a bank narration might belong to a payment something ELSE is confirming at this
 * exact moment (a claim approval, a Co-op callback, a plain M-Pesa callback). The bug an earlier version of
 * {@code BankCreditService} had: it read the payment UNLOCKED, decided from that stale snapshot that it was
 * still unpaid, and only THEN called {@code confirmSuccess} — which correctly no-ops on an already-SUCCESS
 * row (it has its own lock and its own idempotent early return), so the payment and order were never
 * corrupted. But {@code confirmAndMatch}'s own next lines ran regardless of that no-op and marked the
 * credit MATCHED anyway — silently hiding exactly the "this order is already paid, possible duplicate or
 * overpayment" case the class exists to catch. Pinned on that final, observable state (MATCHED vs left
 * UNMATCHED with a note), not on timing: a blocked call proves nothing by itself here, since {@code
 * confirmSuccess}'s own lock (from an earlier fix) makes even the buggy code block eventually, just one
 * call deeper — confirmed by reverting the fix and re-running this test, which still failed on the final
 * state even though it also still blocked partway through.
 */
@SpringBootTest
@ActiveProfiles("test")
class BankCreditLockOrderTest {

    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private PaymentReferenceGenerator referenceGenerator;
    @Autowired private BankCreditService creditService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void autoMatchNeverMarksACreditMatchedToAPaymentThatTurnedOutToBeAlreadyPaid() throws Exception {
        String reference = referenceGenerator.generate();
        UUID paymentId = newBankOrder("BANKLOCK-" + UUID.randomUUID().toString().substring(0, 8), reference);

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch paymentLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Stands in for the payment being confirmed some other way (M-Pesa, a claim, Co-op) at the
            // exact moment the bank credit's auto-match is deciding whether this reference is still unpaid.
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                PaymentRecord payment = paymentRecordRepository.lockById(paymentId).orElseThrow();
                payment.setStatus(PaymentRecord.PaymentStatus.SUCCESS);
                payment.setProviderReference("CONCURRENT-CONFIRM-" + UUID.randomUUID());
                paymentRecordRepository.save(payment);
                paymentLocked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(paymentLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<BankCredit> matched = pool.submit(() -> creditService.record(
                    "BANKTXN-" + UUID.randomUUID(), new BigDecimal("500.00"), "Jane Doe", null,
                    "Transfer ref " + reference, Instant.now(), BankCredit.Source.MANUAL));

            Thread.sleep(800);
            assertThat(matched.isDone()).as("auto-match is waiting on the payment row").isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            BankCredit result = matched.get(30, TimeUnit.SECONDS);
            assertThat(result.getStatus())
                    .as("the payment turned out to already be paid — this credit must be left for a person, never silently matched")
                    .isEqualTo(BankCredit.CreditStatus.UNMATCHED);
            assertThat(result.getResolutionNote()).containsIgnoringCase("already paid");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private UUID newBankOrder(String orderNo, String reference) {
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(orderNo);
        order.setGuestEmail("banklock@example.com");
        order.setGuestPhone("254700000000");
        order.setFulfilmentType(FulfilmentType.PICKUP);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setSubtotal(new BigDecimal("500.00"));
        order.setVatTotal(BigDecimal.ZERO);
        order.setGrandTotal(new BigDecimal("500.00"));
        order = orderRepository.save(order);

        PaymentRecord payment = new PaymentRecord();
        payment.setOrder(order);
        payment.setProvider("BANK");
        payment.setMethod(PaymentRecord.PaymentMethod.BANK);
        payment.setAmount(new BigDecimal("500.00"));
        payment.setStatus(PaymentRecord.PaymentStatus.INITIATED);
        payment.setPaymentReference(reference);
        payment.setExpiresAt(Instant.now().plusSeconds(2700));
        return paymentRecordRepository.save(payment).getId();
    }
}
