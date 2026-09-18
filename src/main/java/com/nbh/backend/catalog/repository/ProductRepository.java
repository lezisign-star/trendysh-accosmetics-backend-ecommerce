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
            """)
    Page<Product> searchPublished(@Param("q") String q, @Param("categorySlug") String categorySlug, Pageable pageable);

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
