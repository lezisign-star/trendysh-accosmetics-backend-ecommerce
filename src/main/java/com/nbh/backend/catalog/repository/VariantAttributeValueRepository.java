package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.VariantAttributeValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VariantAttributeValueRepository
        extends JpaRepository<VariantAttributeValue, VariantAttributeValue.Id> {

    @Query("""
            SELECT vav FROM VariantAttributeValue vav
            JOIN FETCH vav.attributeValue av
            JOIN FETCH av.attribute a
            WHERE vav.variant.id IN :variantIds
            """)
    List<VariantAttributeValue> findByVariantIdIn(@Param("variantIds") Collection<UUID> variantIds);
}
