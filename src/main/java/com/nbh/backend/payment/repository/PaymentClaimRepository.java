package com.nbh.backend.payment.repository;

import com.nbh.backend.payment.entity.PaymentClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentClaimRepository extends JpaRepository<PaymentClaim, UUID> {

    @EntityGraph(attributePaths = {"payment", "payment.order"})
    List<PaymentClaim> findByStatusOrderByCreatedAtAsc(PaymentClaim.ClaimStatus status);

    /** Every code sent for one payment, newest first: what the customer's pay page needs to know. */
    List<PaymentClaim> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    /** Every code sent for these payments, newest first, in one query (the staff "awaiting payment" list). */
    @EntityGraph(attributePaths = {"payment"})
    List<PaymentClaim> findByPaymentIdInOrderByCreatedAtDesc(Collection<UUID> paymentIds);

    /** Every code sent for one order, newest first (the staff order panel). */
    @Query("select c from PaymentClaim c where c.payment.order.id = :orderId order by c.createdAt desc")
    List<PaymentClaim> findByOrderIdNewestFirst(@Param("orderId") UUID orderId);

    /** A claim's status next to the order it belongs to; a projection, so the order list never loads entities. */
    interface OrderClaimStatus {
        UUID getOrderId();

        PaymentClaim.ClaimStatus getStatus();
    }

    @Query("select c.payment.order.id as orderId, c.status as status from PaymentClaim c "
            + "where c.payment.order.id in :orderIds")
    List<OrderClaimStatus> statusesForOrders(@Param("orderIds") Collection<UUID> orderIds);

    long countByPaymentIdAndStatus(UUID paymentId, PaymentClaim.ClaimStatus status);

    long countByPaymentId(UUID paymentId);

    boolean existsByPaymentIdAndStatus(UUID paymentId, PaymentClaim.ClaimStatus status);

    /** Same transaction code twice for one payment is a double-submit, not a second claim. */
    boolean existsByPaymentIdAndTransactionCodeIgnoreCase(UUID paymentId, String transactionCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentClaim c where c.id = :id")
    Optional<PaymentClaim> lockById(UUID id);
}
