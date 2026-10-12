package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductOptionValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductOptionValueRepository extends JpaRepository<ProductOptionValue, UUID> {

    List<ProductOptionValue> findByProductIdOrderByKindAscPositionAsc(UUID productId);
}
