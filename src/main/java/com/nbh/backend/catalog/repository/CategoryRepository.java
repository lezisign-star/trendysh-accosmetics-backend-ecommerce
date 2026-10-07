package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByIsPublishedTrueOrderBySortOrderAsc();

    /** Demo data is "on" while any mock product is published — the one live signal every demo gate uses. */
    @Query("SELECT COUNT(mp) > 0 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true")
    boolean demoDataOn();

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    long countByParentId(UUID parentId);

    /** Same-named categories next to each other (same parent, or both top-level), ignoring case. */
    List<Category> findByParentIdAndNameIgnoreCase(UUID parentId, String name);

    List<Category> findByParentIsNullAndNameIgnoreCase(String name);

    /**
     * Live products directly in a category (however many categories each belongs to — Phase 3,
     * 2026-09-24). Rows: [categoryId, count]. A caller that wants a top-level category's count to
     * include its subcategories' products sums this with the same query filtered to its children.
     */
    @Query("""
            SELECT c.id, COUNT(DISTINCT p)
            FROM Product p JOIN p.categories c
            WHERE p.deleted = false AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            GROUP BY c.id
            """)
    List<Object[]> liveProductCountsByCategory();

    /** Every product (including soft-deleted ones) still in this category — these block deleting it. */
    @Query("SELECT COUNT(DISTINCT p) FROM Product p JOIN p.categories c WHERE c.id = :id")
    long countProductsReferencing(UUID id);
}
