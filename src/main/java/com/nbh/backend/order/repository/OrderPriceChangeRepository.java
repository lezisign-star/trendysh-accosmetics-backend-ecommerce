package com.nbh.backend.order.repository;

import com.nbh.backend.order.entity.OrderPriceChange;
import com.nbh.backend.order.entity.OrderPriceChange.Acknowledgement;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderPriceChangeRepository extends JpaRepository<OrderPriceChange, UUID> {

    /** Every price change on one order, in the order they were recorded. */
    @Query("SELECT c FROM OrderPriceChange c JOIN FETCH c.order WHERE c.order.id = :orderId ORDER BY c.createdAt, c.id")
    List<OrderPriceChange> findByOrderId(@Param("orderId") UUID orderId);

    /** Taken before an answer is recorded, so two people answering the same change at once cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM OrderPriceChange c WHERE c.id = :id")
    Optional<OrderPriceChange> findWithLockById(@Param("id") UUID id);

    /**
     * Price changes in a period (placed at or after {@code from}, before {@code to}) with one of these answers, newest first,
     * among the orders the staff screens are showing: the real orders, or the demo ones while the demo catalogue is on.
     */
    @Query(value = """
            SELECT c FROM OrderPriceChange c JOIN FETCH c.order o
            WHERE c.createdAt >= :from AND c.createdAt < :to AND c.ackStatus IN :statuses
              AND ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            ORDER BY c.createdAt DESC, c.id
            """,
            countQuery = """
            SELECT COUNT(c) FROM OrderPriceChange c JOIN c.order o
            WHERE c.createdAt >= :from AND c.createdAt < :to AND c.ackStatus IN :statuses
              AND ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    Page<OrderPriceChange> inPeriod(@Param("from") Instant from, @Param("to") Instant to,
                                    @Param("statuses") Collection<Acknowledgement> statuses, Pageable pageable);

    /** The changes with this answer that name this person as the confirmer, oldest first (the one waiting longest is on top). */
    @Query("""
            SELECT c FROM OrderPriceChange c JOIN FETCH c.order o
            WHERE c.ackStatus = :status AND c.confirmerId = :confirmerId
              AND ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            ORDER BY c.createdAt, c.id
            """)
    List<OrderPriceChange> namedTo(@Param("confirmerId") UUID confirmerId, @Param("status") Acknowledgement status);
}
