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
     * Which live products are directly in which category (however many categories each belongs to — Phase 3,
     * 2026-09-24). Rows: [categoryId, productId]. A caller that wants a top-level category's count to include its
     * subcategories' products adds up its children's. Pairs rather than counts, so a product that is in a category both
     * ways (see {@link #liveVariantCategoryPairs}) is counted once.
     */
    @Query("""
            SELECT c.id, p.id
            FROM Product p JOIN p.categories c
            WHERE p.deleted = false AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    List<Object[]> liveProductCategoryPairs();

    /**
     * The same for a range whose categories differ (V68): the shop lists such a variant under its own category, so its
     * product is in that category too. Rows: [categoryId, productId].
     */
    @Query("""
            SELECT v.category.id, p.id
            FROM ProductVariant v JOIN v.product p
            WHERE v.category IS NOT NULL AND p.categoryVaries = true AND v.deleted = false AND p.deleted = false
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    List<Object[]> liveVariantCategoryPairs();

    /** Every product (including soft-deleted ones) still in this category — these block deleting it. */
    @Query("SELECT COUNT(DISTINCT p) FROM Product p JOIN p.categories c WHERE c.id = :id")
    long countProductsReferencing(UUID id);

    /**
     * Every variant (deleted ones too) that has this category as its own (V68): a foreign key, so it blocks deleting the
     * category just like a product does.
     */
    @Query("SELECT COUNT(v) FROM ProductVariant v WHERE v.category.id = :id")
    long countVariantsFiledUnder(UUID id);
}
