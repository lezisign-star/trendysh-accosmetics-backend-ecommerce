package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.entity.StockDocument;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StockDocumentRepository extends JpaRepository<StockDocument, UUID> {
    Optional<StockDocument> findByIdempotencyKey(String idempotencyKey);

    Optional<StockDocument> findByDocumentNo(String documentNo);

    /**
     * For reversing a document: two reversals of the same document at once would each see "not yet
     * reversed", each post the undoing movement, and take the stock out twice. Locked, the second waits
     * and then sees the first's result. No entity graph, so {@code FOR UPDATE} has no outer join to reject.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from StockDocument d where d.id = :id")
    Optional<StockDocument> lockById(@Param("id") UUID id);
}
