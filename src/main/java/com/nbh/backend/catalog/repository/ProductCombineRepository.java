package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.ProductCombine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductCombineRepository extends JpaRepository<ProductCombine, UUID> {

    List<ProductCombine> findByMainProductIdOrderByCreatedAtDesc(UUID mainProductId);

    /** The products here that have a combine into them not split back yet. */
    List<ProductCombine> findByMainProductIdInAndSplitAtIsNull(Collection<UUID> mainProductIds);
}
