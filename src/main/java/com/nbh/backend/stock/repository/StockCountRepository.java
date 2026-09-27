package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.entity.StockCount;
import com.nbh.backend.stock.entity.StockCountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StockCountRepository extends JpaRepository<StockCount, UUID> {

    @EntityGraph(attributePaths = {"lines", "lines.variant", "lines.variant.product"})
    Optional<StockCount> findById(UUID id);

    @Query("""
            SELECT sc FROM StockCount sc
            WHERE (:status IS NULL OR sc.status = :status)
            ORDER BY sc.createdAt DESC
            """)
    Page<StockCount> search(@Param("status") StockCountStatus status, Pageable pageable);
}
