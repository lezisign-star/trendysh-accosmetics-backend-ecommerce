package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.entity.StockDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StockDocumentRepository extends JpaRepository<StockDocument, UUID> {
    Optional<StockDocument> findByIdempotencyKey(String idempotencyKey);

    Optional<StockDocument> findByDocumentNo(String documentNo);
}
