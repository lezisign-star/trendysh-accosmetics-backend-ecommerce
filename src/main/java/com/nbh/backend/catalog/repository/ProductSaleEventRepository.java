package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductSaleEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ProductSaleEventRepository extends JpaRepository<ProductSaleEvent, UUID> {

    @Query("SELECT COALESCE(SUM(s.qty), 0) FROM ProductSaleEvent s WHERE s.product.id = :productId")
    BigDecimal totalUnitsSold(@Param("productId") UUID productId);

    /** Batch aggregate for a page of products — same batching reasoning as ProductReviewRepository. */
    @Query("""
            SELECT s.product.id AS productId, COALESCE(SUM(s.qty), 0) AS totalQty
            FROM ProductSaleEvent s
            WHERE s.product.id IN :productIds
            GROUP BY s.product.id
            """)
    List<SoldSummary> aggregateUnitsSold(@Param("productIds") List<UUID> productIds);

    interface SoldSummary {
        UUID getProductId();
        BigDecimal getTotalQty();
    }
}
