package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductOptionValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductOptionValueRepository extends JpaRepository<ProductOptionValue, UUID> {

    List<ProductOptionValue> findByProductIdOrderByKindAscPositionAsc(UUID productId);

    /** Every colour and size of the real products still in the catalogue (not removed, not demo data). */
    @org.springframework.data.jpa.repository.Query("""
            SELECT o FROM ProductOptionValue o
            WHERE EXISTS (SELECT 1 FROM Product p WHERE p.id = o.productId AND p.deleted = false AND p.isMockData = false)
            """)
    List<ProductOptionValue> findOfLiveProducts();
}
