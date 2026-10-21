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

    /** One variant with its product already loaded — for callers that build a message from both outside a transaction. */
    @Query("SELECT v FROM ProductVariant v JOIN FETCH v.product WHERE v.id = :id")
    Optional<ProductVariant> findWithProductById(@Param("id") UUID id);

    /**
     * Every variant that could actually run out on the shelf: sellable, not a kit (kits hold no stock of
     * their own — their components do), on a product that is live in the shop. Product is fetched in the
     * same query because the stock forecast reads its name for every row.
     */
    @Query("""
            SELECT v FROM ProductVariant v JOIN FETCH v.product p
            WHERE v.deleted = false AND v.isActive = true AND v.isKit = false
              AND v.sellingPrice IS NOT NULL
              AND p.deleted = false AND p.isPublished = true
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    List<ProductVariant> findForecastable();

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
     * here instead: not deleted, active, and has a price. Same {@code CAST(:q AS string)}
     * null-parameter fix and explicit {@code LEFT JOIN c.parent parent} landmine-avoidance as
     * {@code ProductRepository}'s equivalent queries — see their javadoc for why both are required.
     *
     * {@code q} matches as much of "what this product actually is" as reasonably makes it
     * findable (2026-09-29 — client's own request, "as much as possible: category, description,
     * name, sku, inclusion in a kit"): product/variant name, product description, SKU, this
     * variant's own category and its parent, and — for a kit's own line only — the profession it's
     * curated for (so "brow artist" finds a kit named something else entirely, as long as it's
     * filed under that profession). {@code includeKits}: kits have their own pages (owner, 2026-10-19), so the browse
     * grid leaves them out and only a search (which finds them as kit cards) asks for them. {@code Kit} isn't reachable from {@code ProductVariant} via a
     * mapped association (the FK lives on {@code kit.variant_id}, not the other way around), hence
     * the explicit ad-hoc {@code ON} join rather than a normal path join.
     */
    @Query("""
            SELECT DISTINCT v FROM ProductVariant v
            JOIN v.product p
            LEFT JOIN p.categories c
            LEFT JOIN c.parent parent
            LEFT JOIN v.category vc
            LEFT JOIN vc.parent vparent
            LEFT JOIN Kit kit ON kit.variant = v
            LEFT JOIN kit.profession prof
            WHERE v.deleted = false AND v.isActive = true AND v.sellingPrice IS NOT NULL
              AND p.isPublished = true AND p.deleted = false
              AND (v.isKit = false OR :includeKits = true)
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (:q IS NULL
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(v.variantName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(p.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(v.sku) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(parent.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(vc.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(prof.label) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND (:categorySlug IS NULL
                   OR ((vc IS NULL OR p.categoryVaries = false) AND (c.slug = :categorySlug OR parent.slug = :categorySlug))
                   OR (p.categoryVaries = true AND (vc.slug = :categorySlug OR vparent.slug = :categorySlug)))
              AND (:popular IS NULL OR p.isPopular = :popular)
              AND (:newArrival IS NULL OR p.isNewArrival = :newArrival)
              AND (:deal IS NULL OR :deal = false
                   OR (v.dealPrice IS NOT NULL AND v.dealEndsAt > CURRENT_TIMESTAMP))
            ORDER BY v.createdAt DESC, v.id DESC
            """)
    Page<ProductVariant> searchSellable(@Param("q") String q, @Param("categorySlug") String categorySlug,
                                         @Param("deal") Boolean deal, @Param("popular") Boolean popular,
                                         @Param("newArrival") Boolean newArrival,
                                         @Param("includeKits") boolean includeKits, Pageable pageable);

    /** Every sellable variant of the same product, in display order — the storefront's sibling strip. */
    List<ProductVariant> findByProductIdAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNullOrderBySortOrderAsc(UUID productId);

    /** Batched version for a page of products — same batching reasoning as {@link #findByProductIdIn}. */
    List<ProductVariant> findByProductIdInAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNull(Collection<UUID> productIds);

    /**
     * Generated variants (see {@code variantgen}) that have no photo of their own and none waiting for review: the
     * "needs images" list. Newest first.
     */
    @Query("""
            SELECT v FROM ProductVariant v JOIN FETCH v.product p
            WHERE v.deleted = false AND p.deleted = false AND v.generatedFromVariantId IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM ProductImage i WHERE i.variant = v)
              AND NOT EXISTS (SELECT 1 FROM com.nbh.backend.imagecleanup.ImageDraft d WHERE d.variantId = v.id)
              AND NOT EXISTS (SELECT 1 FROM com.nbh.backend.variantjobs.VariantJobItem ji WHERE ji.variantId = v.id)
            ORDER BY v.createdAt DESC
            """)
    List<ProductVariant> findGeneratedNeedingImages();

    /**
     * Of these variants, the ones whose "photo failed" no longer needs doing anything about: the variant was removed,
     * or it now has a photo of its own (staff uploaded one, or another run made it).
     */
    @Query("""
            SELECT v.id FROM ProductVariant v
            WHERE v.id IN :ids
              AND (v.deleted = true OR EXISTS (SELECT 1 FROM ProductImage i WHERE i.variant = v))
            """)
    List<UUID> findIdsRemovedOrWithOwnPhoto(@Param("ids") Collection<UUID> ids);

    List<ProductVariant> findByGenerationBatchId(UUID generationBatchId);

    /** Whether this product stands for a kit (its one variant is the kit's own). */
    @Query("SELECT COUNT(v) > 0 FROM ProductVariant v WHERE v.product.id = :productId AND v.isKit = true")
    boolean existsKitVariant(@Param("productId") UUID productId);

    /** Live variants (not kits) of real products not yet looked at in "Tidy colours & sizes", with their product. */
    @Query("""
            SELECT v FROM ProductVariant v JOIN FETCH v.product p
            WHERE v.deleted = false AND v.isKit = false AND p.deleted = false AND p.isMockData = false
              AND p.optionsReviewedAt IS NULL
            """)
    List<ProductVariant> findForOptionsReview();
}
