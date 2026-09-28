package com.nbh.backend.payment.repository;

import com.nbh.backend.payment.entity.PaymentRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, UUID> {
    List<PaymentRecord> findByOrderId(UUID orderId);

    Optional<PaymentRecord> findByIdempotencyKey(String idempotencyKey);

    /**
     * §4 C4 fix: the M-Pesa callback used to load every payment record ever
     * created (findAll().stream().filter(...)) to correlate a CheckoutRequestID
     * — unbounded memory growth on the hottest, most latency-sensitive path in
     * the system. This is the natural correlation key; backed by a unique index
     * (see V11 migration).
     */
    Optional<PaymentRecord> findByProviderReference(String providerReference);

    /**
     * Staff "awaiting payment" queue: bank orders not yet paid, oldest first (order fetched, no N+1).
     * Includes orders that EXPIRED within the last window ({@code expiredSince}) — a customer can still
     * pay after the hold lapses, and staff need to find that order to confirm it — but not cancelled or
     * failed ones, which can never be marked paid, nor months-old expired ones.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"order"})
    @Query("select p from PaymentRecord p where p.paymentReference is not null "
            + "and p.status in (com.nbh.backend.payment.entity.PaymentRecord.PaymentStatus.INITIATED, "
            + "com.nbh.backend.payment.entity.PaymentRecord.PaymentStatus.PENDING) "
            + "and (p.order.status = com.nbh.backend.order.entity.OrderStatus.PENDING_PAYMENT "
            + "or (p.order.status = com.nbh.backend.order.entity.OrderStatus.EXPIRED "
            + "and p.order.updatedAt >= :expiredSince)) "
            + "order by p.createdAt asc")
    List<PaymentRecord> findAwaitingBankPayments(@org.springframework.data.repository.query.Param("expiredSince") java.time.Instant expiredSince);

    /** Bank collection: the customer-quoted reference (V19), backed by a unique index. */
    Optional<PaymentRecord> findByPaymentReference(String paymentReference);

    /**
     * §4 C2 fix: confirmSuccess/markFailed used an unlocked findById, so a
     * Daraja callback retry racing a staff manual-confirm (or a manual-fail
     * racing an arriving success callback) could both read the same status and
     * both write — e.g. markFailed releasing the stock of an order confirmSuccess
     * had already marked PAID. Same lock-then-recheck pattern already proven for
     * StockReservation (audit finding F4) and StockBalance.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentRecord p where p.id = :id")
    Optional<PaymentRecord> lockById(UUID id);
}
