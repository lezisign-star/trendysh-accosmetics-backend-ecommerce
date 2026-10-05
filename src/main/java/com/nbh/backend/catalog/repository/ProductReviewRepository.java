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

    /**
     * Staff reading reviews: optional status filter, newest first; product and order are loaded with the page.
     *
     * <p>The ~100 reviews {@code MockDataSeederService} attaches to demo products are all APPROVED, so they
     * showed under "Showing" and "All" even with demo data switched off (found in the 2026-10-05 audit of
     * where demo rows still surfaced). Same rule as every other staff list: demo reviews only while demo
     * data is on (any demo product currently published), and then only those; otherwise only real ones.
     */
    @Query(value = """
            SELECT r FROM ProductReview r JOIN FETCH r.product p JOIN FETCH r.order
            WHERE (:status IS NULL OR r.status = :status)
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM ProductReview r JOIN r.product p
            WHERE (:status IS NULL OR r.status = :status)
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    Page<ProductReview> searchForStaff(@org.springframework.data.repository.query.Param("status") ProductReview.ReviewStatus status, Pageable pageable);

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
