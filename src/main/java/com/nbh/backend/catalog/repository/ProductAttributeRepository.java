package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductAttribute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductAttributeRepository extends JpaRepository<ProductAttribute, UUID> {
    boolean existsByCode(String code);

    Optional<ProductAttribute> findByCode(String code);

    List<ProductAttribute> findAllByOrderBySortOrderAsc();
}
