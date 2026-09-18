package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByIsPublishedTrueOrderBySortOrderAsc();

    Optional<Category> findBySlug(String slug);
}
