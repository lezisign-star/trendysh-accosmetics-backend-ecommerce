package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {
    Optional<ProductVariant> findBySku(String sku);

    List<ProductVariant> findByProductId(UUID productId);

    /** Batch fetch for a page of products — avoids one query per product (audit finding F12). */
    List<ProductVariant> findByProductIdIn(Collection<UUID> productIds);
}
