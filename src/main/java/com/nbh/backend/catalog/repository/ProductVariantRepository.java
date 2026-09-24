package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {
    Optional<ProductVariant> findBySku(String sku);

    List<ProductVariant> findByProductId(UUID productId);

    /** Batch fetch for a page of products — avoids one query per product (audit finding F12). */
    List<ProductVariant> findByProductIdIn(Collection<UUID> productIds);

    /**
     * Excludes soft-deleted rows — found live while building Phase 3's editor (2026-09-24): nothing
     * previously filtered {@code deleted}, so a removed variant kept showing in the admin list/editor.
     */
    List<ProductVariant> findByProductIdAndDeletedFalseOrderBySortOrderAsc(UUID productId);

    List<ProductVariant> findByProductIdInAndDeletedFalse(Collection<UUID> productIds);

    /** Backs auto-generated SKUs (see {@code ProductService.generateSku}) — atomic, race-free. */
    @Query(value = "SELECT nextval('variant_sku_seq')", nativeQuery = true)
    long nextSkuSequence();
}
