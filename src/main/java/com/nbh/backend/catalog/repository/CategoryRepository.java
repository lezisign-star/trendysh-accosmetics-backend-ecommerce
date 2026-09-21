package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByIsPublishedTrueOrderBySortOrderAsc();

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    long countByParentId(UUID parentId);

    /** Live products per category id (as category). Rows: [categoryId, count]. */
    @Query("SELECT p.category.id, COUNT(p) FROM Product p WHERE p.deleted = false AND p.category IS NOT NULL GROUP BY p.category.id")
    List<Object[]> liveProductCountsByCategory();

    /** Live products per category id (as subcategory). Rows: [categoryId, count]. */
    @Query("SELECT p.subcategory.id, COUNT(p) FROM Product p WHERE p.deleted = false AND p.subcategory IS NOT NULL GROUP BY p.subcategory.id")
    List<Object[]> liveProductCountsBySubcategory();

    /** Every product (including soft-deleted ones) that still points at the category — these block deleting it. */
    @Query("SELECT COUNT(p) FROM Product p WHERE p.category.id = :id OR p.subcategory.id = :id")
    long countProductsReferencing(UUID id);
}
