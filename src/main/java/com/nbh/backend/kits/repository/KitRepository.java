package com.nbh.backend.kits.repository;

import com.nbh.backend.kits.entity.Kit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KitRepository extends JpaRepository<Kit, UUID> {

    Optional<Kit> findByVariantId(UUID variantId);

    /** Batched, for the catalogue's card page (which kits are on it) — avoids an N+1 per kit card. */
    List<Kit> findByVariantIdIn(Collection<UUID> variantIds);

    boolean existsByVariantId(UUID variantId);

    Page<Kit> findAll(Pageable pageable);

    /** Admin list search — {@code q} matches the kit's own product name, same short-circuit-when-null
     *  pattern as {@code ProductRepository.searchAdmin}. */
    @Query("SELECT COUNT(mp) > 0 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true")
    boolean demoDataOn();

    @Query("""
            SELECT k FROM Kit k
            JOIN k.variant v
            JOIN v.product p
            WHERE (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (:professionId IS NULL OR k.profession.id = :professionId)
            """)
    Page<Kit> searchAdmin(@Param("q") String q, @Param("professionId") UUID professionId, Pageable pageable);
}
