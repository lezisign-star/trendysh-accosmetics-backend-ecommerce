package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {
    List<ProductImage> findByProductIdOrderBySortOrderAsc(UUID productId);

    /**
     * Photos the AI cleanup can start from: a product-level primary photo that is not itself AI-made, on a live
     * product that has not been cleaned yet and belongs to whichever data set (real or demo) is currently on.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT i FROM ProductImage i
            WHERE i.isPrimary = true AND i.variant IS NULL AND i.source <> :ai
              AND i.product.deleted = false
              AND NOT EXISTS (SELECT 1 FROM ProductImage c WHERE c.product = i.product AND c.source = :ai)
              AND ((i.product.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true))
                   OR (i.product.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            ORDER BY i.createdAt ASC
            """)
    List<ProductImage> cleanupCandidates(@org.springframework.data.repository.query.Param("ai") ProductImage.ImageSource ai,
                                          org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(i) FROM ProductImage i
            WHERE i.isPrimary = true AND i.variant IS NULL AND i.source <> :ai
              AND i.product.deleted = false
              AND NOT EXISTS (SELECT 1 FROM ProductImage c WHERE c.product = i.product AND c.source = :ai)
              AND ((i.product.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true))
                   OR (i.product.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    long countCleanupCandidates(@org.springframework.data.repository.query.Param("ai") ProductImage.ImageSource ai);

    /** Batch fetch for a page of products — same batching reasoning as F12. */
    List<ProductImage> findByProductIdInOrderBySortOrderAsc(Collection<UUID> productIds);
}
