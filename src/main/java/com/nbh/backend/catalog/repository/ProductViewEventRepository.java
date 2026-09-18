package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductViewEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProductViewEventRepository extends JpaRepository<ProductViewEvent, UUID> {

    long countByProductId(UUID productId);

    @Query("""
            SELECT v.product.id AS productId, COUNT(v) AS viewCount
            FROM ProductViewEvent v
            WHERE v.product.id IN :productIds
            GROUP BY v.product.id
            """)
    List<ViewSummary> aggregateViews(@Param("productIds") List<UUID> productIds);

    interface ViewSummary {
        UUID getProductId();
        Long getViewCount();
    }
}
