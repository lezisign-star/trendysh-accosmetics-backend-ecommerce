package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProductReviewRepository extends JpaRepository<ProductReview, UUID> {

    boolean existsByOrderIdAndProductId(UUID orderId, UUID productId);

    Page<ProductReview> findByProductIdAndStatusOrderByCreatedAtDesc(
            UUID productId, ProductReview.ReviewStatus status, Pageable pageable);

    long countByProductIdAndStatus(UUID productId, ProductReview.ReviewStatus status);

    /** Batch aggregate for a page of products — avoids one query per product, same reasoning as F12. */
    @Query("""
            SELECT r.product.id AS productId, AVG(r.rating) AS avgRating, COUNT(r) AS reviewCount
            FROM ProductReview r
            WHERE r.status = :status AND r.product.id IN :productIds
            GROUP BY r.product.id
            """)
    List<RatingSummary> aggregateRatings(@Param("productIds") List<UUID> productIds,
                                          @Param("status") ProductReview.ReviewStatus status);

    interface RatingSummary {
        UUID getProductId();
        Double getAvgRating();
        Long getReviewCount();
    }
}
