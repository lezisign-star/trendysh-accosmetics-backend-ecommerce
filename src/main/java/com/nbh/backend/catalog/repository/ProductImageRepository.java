package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {
    List<ProductImage> findByProductIdOrderBySortOrderAsc(UUID productId);

    /** One option's own photos (the photos filed under it), in gallery order. */
    List<ProductImage> findByVariantIdOrderBySortOrderAsc(UUID variantId);

    /** True while any photo row still points at this exact URL (a duplicated product shares its photos' URLs). */
    boolean existsByUrl(String url);

    /**
     * Photos the AI cleanup can start from: an option's own main photo that is not itself AI-made, on a live option
     * of a live product (never a kit: its thumbnail is a photo of the professional at work, not a product shot) that has not been cleaned yet and belongs to whichever data set (real or demo) is currently
     * on. Photos still at product level (unassigned, on a product with several options) belong to no option, so they
     * are never a starting point.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT i FROM ProductImage i
            WHERE i.isPrimary = true AND i.variant IS NOT NULL AND i.source <> :ai
              AND i.variant.deleted = false AND i.product.deleted = false
              AND i.variant.isKit = false
              AND NOT EXISTS (SELECT 1 FROM ProductImage c WHERE c.variant = i.variant AND c.source = :ai)
              AND ((i.product.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true))
                   OR (i.product.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            ORDER BY i.createdAt ASC
            """)
    List<ProductImage> cleanupCandidates(@org.springframework.data.repository.query.Param("ai") ProductImage.ImageSource ai,
                                          org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(i) FROM ProductImage i
            WHERE i.isPrimary = true AND i.variant IS NOT NULL AND i.source <> :ai
              AND i.variant.deleted = false AND i.product.deleted = false
              AND i.variant.isKit = false
              AND NOT EXISTS (SELECT 1 FROM ProductImage c WHERE c.variant = i.variant AND c.source = :ai)
              AND ((i.product.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true))
                   OR (i.product.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    long countCleanupCandidates(@org.springframework.data.repository.query.Param("ai") ProductImage.ImageSource ai);

    /** Batch fetch for a page of products — same batching reasoning as F12. */
    List<ProductImage> findByProductIdInOrderBySortOrderAsc(Collection<UUID> productIds);
}
