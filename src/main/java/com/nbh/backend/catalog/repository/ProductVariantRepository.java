package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductVariant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The storefront's one-card-per-variant browse (Phase 3, 2026-09-24 — replaces the old
     * one-card-per-product catalogue). {@code v.isSellable()} can't be expressed in JPQL directly
     * (it's a plain Java method, not a mapped property), so its three conditions are spelled out
     * here instead: not deleted, active, and has a price. {@code q} matches the product name or
     * this variant's own name. Same {@code CAST(:q AS string)} null-parameter fix and explicit
     * {@code LEFT JOIN c.parent parent} landmine-avoidance as {@code ProductRepository}'s
     * equivalent queries — see their javadoc for why both are required.
     */
    @Query("""
            SELECT DISTINCT v FROM ProductVariant v
            JOIN v.product p
            LEFT JOIN p.categories c
            LEFT JOIN c.parent parent
            WHERE v.deleted = false AND v.isActive = true AND v.sellingPrice IS NOT NULL
              AND p.isPublished = true AND p.deleted = false
              AND (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(v.variantName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND (:categorySlug IS NULL OR c.slug = :categorySlug OR parent.slug = :categorySlug)
              AND (:popular IS NULL OR p.isPopular = :popular)
              AND (:newArrival IS NULL OR p.isNewArrival = :newArrival)
              AND (:deal IS NULL OR :deal = false
                   OR (v.dealPrice IS NOT NULL AND v.dealEndsAt > CURRENT_TIMESTAMP))
            """)
    Page<ProductVariant> searchSellable(@Param("q") String q, @Param("categorySlug") String categorySlug,
                                         @Param("deal") Boolean deal, @Param("popular") Boolean popular,
                                         @Param("newArrival") Boolean newArrival, Pageable pageable);

    /** Every sellable variant of the same product, in display order — the storefront's sibling strip. */
    List<ProductVariant> findByProductIdAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNullOrderBySortOrderAsc(UUID productId);

    /** Batched version for a page of products — same batching reasoning as {@link #findByProductIdIn}. */
    List<ProductVariant> findByProductIdInAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNull(Collection<UUID> productIds);
}
