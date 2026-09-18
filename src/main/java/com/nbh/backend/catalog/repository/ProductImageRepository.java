package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {
    List<ProductImage> findByProductIdOrderBySortOrderAsc(UUID productId);

    /** Batch fetch for a page of products — same batching reasoning as F12. */
    List<ProductImage> findByProductIdInOrderBySortOrderAsc(Collection<UUID> productIds);
}
