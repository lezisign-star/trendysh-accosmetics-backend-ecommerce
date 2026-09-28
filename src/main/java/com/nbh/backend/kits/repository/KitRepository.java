package com.nbh.backend.kits.repository;

import com.nbh.backend.kits.entity.Kit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface KitRepository extends JpaRepository<Kit, UUID> {

    Optional<Kit> findByVariantId(UUID variantId);

    boolean existsByVariantId(UUID variantId);

    Page<Kit> findAll(Pageable pageable);
}
