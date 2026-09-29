package com.nbh.backend.kits.repository;

import com.nbh.backend.kits.entity.Kit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface KitRepository extends JpaRepository<Kit, UUID> {

    Optional<Kit> findByVariantId(UUID variantId);

    boolean existsByVariantId(UUID variantId);

    Page<Kit> findAll(Pageable pageable);

    /** Admin list search — {@code q} matches the kit's own product name, same short-circuit-when-null
     *  pattern as {@code ProductRepository.searchAdmin}. */
    @Query("""
            SELECT k FROM Kit k
            JOIN k.variant v
            JOIN v.product p
            WHERE (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND (:professionId IS NULL OR k.profession.id = :professionId)
            """)
    Page<Kit> searchAdmin(@Param("q") String q, @Param("professionId") UUID professionId, Pageable pageable);
}
