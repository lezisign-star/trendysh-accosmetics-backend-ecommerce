package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    boolean existsBySlug(String slug);

    Optional<Product> findBySlugAndIsPublishedTrueAndDeletedFalse(String slug);

    /**
     * Public storefront browse — published/not-deleted only. {@code q} and
     * {@code categorySlug} are optional (a null parameter short-circuits its
     * own clause). Matches a category OR its subcategory since the catalogue
     * only has two levels (session 3's schema).
     *
     * Explicit {@code LEFT JOIN}s are required here — found live: an implicit
     * path navigation like {@code p.subcategory.slug} in a WHERE clause
     * compiles to an INNER join, which silently drops every product whose
     * {@code subcategory} is null (the common case) even when its
     * {@code category} matches, because Hibernate still requires that inner
     * join to succeed for the row to appear at all.
     *
     * {@code CAST(:q AS string)} is also required — found live: with a null
     * {@code q}, PostgreSQL's JDBC driver can't infer a type for that bind
     * parameter from its position inside {@code LOWER(CONCAT(...))} alone and
     * defaults it to {@code bytea}, so every call with no search term (not
     * just an actual search) failed with "function lower(bytea) does not
     * exist". The explicit cast forces varchar, which the null-check on the
     * same parameter never needed since {@code IS NULL} doesn't care about type.
     */
    @Query("""
            SELECT p FROM Product p
            LEFT JOIN p.category c
            LEFT JOIN p.subcategory sc
            WHERE p.isPublished = true AND p.deleted = false
              AND (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(p.shortDescription) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND (:categorySlug IS NULL OR c.slug = :categorySlug OR sc.slug = :categorySlug)
              AND (:popular IS NULL OR p.isPopular = :popular)
              AND (:newArrival IS NULL OR p.isNewArrival = :newArrival)
              AND (:deal IS NULL OR :deal = false OR EXISTS (
                        SELECT 1 FROM ProductVariant v
                        WHERE v.product = p AND v.dealPrice IS NOT NULL AND v.dealEndsAt > CURRENT_TIMESTAMP))
            """)
    Page<Product> searchPublished(@Param("q") String q, @Param("categorySlug") String categorySlug,
                                   @Param("deal") Boolean deal, @Param("popular") Boolean popular,
                                   @Param("newArrival") Boolean newArrival, Pageable pageable);

    /**
     * Staff product list: every optional filter short-circuits when null. {@code q} also matches a
     * variant SKU. {@code stock} is IN (something sellable) or OUT (nothing sellable); the same
     * sellable definition as the public catalogue (on hand minus reserved, at SHOP — branches/
     * locations were removed 2026-09-24, founder decision, see HANDOFF.md).
     * Same {@code CAST(... AS string)} null-parameter fix as {@link #searchPublished}.
     */
    @Query("""
            SELECT p FROM Product p
            LEFT JOIN p.category c
            LEFT JOIN p.subcategory sc
            WHERE p.deleted = :deleted
              AND (:q IS NULL
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR EXISTS (SELECT 1 FROM ProductVariant v
                              WHERE v.product = p AND LOWER(v.sku) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))))
              AND (:categoryId IS NULL OR c.id = :categoryId OR sc.id = :categoryId)
              AND (:published IS NULL OR p.isPublished = :published)
              AND (:popular IS NULL OR p.isPopular = :popular)
              AND (:newArrival IS NULL OR p.isNewArrival = :newArrival)
              AND (:stock IS NULL
                   OR (CAST(:stock AS string) = 'IN' AND EXISTS (
                            SELECT 1 FROM StockBalance b
                            WHERE b.variant.product = p AND b.account = com.nbh.backend.stock.entity.StockAccount.SHOP
                              AND (b.onHandQty - b.reservedQty) > 0))
                   OR (CAST(:stock AS string) = 'OUT' AND NOT EXISTS (
                            SELECT 1 FROM StockBalance b
                            WHERE b.variant.product = p AND b.account = com.nbh.backend.stock.entity.StockAccount.SHOP
                              AND (b.onHandQty - b.reservedQty) > 0)))
            """)
    Page<Product> searchAdmin(@Param("q") String q,
                               @Param("categoryId") UUID categoryId,
                               @Param("published") Boolean published,
                               @Param("stock") String stock,
                               @Param("popular") Boolean popular,
                               @Param("newArrival") Boolean newArrival,
                               @Param("deleted") boolean deleted,
                               Pageable pageable);

    /** Same category or subcategory as the given product, excluding itself — the "related products" rail. */
    @Query("""
            SELECT p FROM Product p
            LEFT JOIN p.category c
            LEFT JOIN p.subcategory sc
            WHERE p.isPublished = true AND p.deleted = false AND p.id <> :excludeId
              AND ((c.id = :categoryId) OR (sc.id = :categoryId)
                   OR (c.id = :subcategoryId) OR (sc.id = :subcategoryId))
            """)
    List<Product> findRelated(@Param("categoryId") UUID categoryId,
                               @Param("subcategoryId") UUID subcategoryId,
                               @Param("excludeId") UUID excludeId,
                               Pageable pageable);
}
