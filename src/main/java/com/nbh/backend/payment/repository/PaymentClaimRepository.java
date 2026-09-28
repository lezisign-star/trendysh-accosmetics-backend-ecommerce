package com.nbh.backend.payment.repository;

import com.nbh.backend.payment.entity.PaymentClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentClaimRepository extends JpaRepository<PaymentClaim, UUID> {

    @EntityGraph(attributePaths = {"payment", "payment.order"})
    List<PaymentClaim> findByStatusOrderByCreatedAtAsc(PaymentClaim.ClaimStatus status);

    long countByPaymentIdAndStatus(UUID paymentId, PaymentClaim.ClaimStatus status);

    long countByPaymentId(UUID paymentId);

    boolean existsByPaymentIdAndStatus(UUID paymentId, PaymentClaim.ClaimStatus status);

    /** Same transaction code twice for one payment is a double-submit, not a second claim. */
    boolean existsByPaymentIdAndTransactionCodeIgnoreCase(UUID paymentId, String transactionCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentClaim c where c.id = :id")
    Optional<PaymentClaim> lockById(UUID id);
}
