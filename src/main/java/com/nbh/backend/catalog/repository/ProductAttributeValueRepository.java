package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductAttributeValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProductAttributeValueRepository extends JpaRepository<ProductAttributeValue, UUID> {
}
