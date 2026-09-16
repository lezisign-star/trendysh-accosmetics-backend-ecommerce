package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.VariantAttributeValue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VariantAttributeValueRepository
        extends JpaRepository<VariantAttributeValue, VariantAttributeValue.Id> {
}
