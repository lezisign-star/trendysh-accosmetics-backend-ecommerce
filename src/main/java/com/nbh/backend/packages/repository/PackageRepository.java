package com.nbh.backend.packages.repository;

import com.nbh.backend.packages.entity.ProductPackage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PackageRepository extends JpaRepository<ProductPackage, UUID> {

    Optional<ProductPackage> findByVariantId(UUID variantId);

    /** The package whose product has this slug (the address of its page in the shop). */
    @Query("SELECT k FROM ProductPackage k JOIN k.variant v JOIN v.product p WHERE p.slug = :slug")
    Optional<ProductPackage> findByProductSlug(@Param("slug") String slug);

    /** Batched, for the catalogue's card page (which packages are on it) — avoids an N+1 per package card. */
    List<ProductPackage> findByVariantIdIn(Collection<UUID> variantIds);

    boolean existsByVariantId(UUID variantId);

    Page<ProductPackage> findAll(Pageable pageable);

    /** Admin list search — {@code q} matches the package's own product name, same short-circuit-when-null
     *  pattern as {@code ProductRepository.searchAdmin}. */
    @Query("SELECT COUNT(mp) > 0 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true")
    boolean demoDataOn();

    @Query("""
            SELECT k FROM ProductPackage k
            JOIN k.variant v
            JOIN v.product p
            WHERE (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (:professionId IS NULL OR k.profession.id = :professionId
                   OR EXISTS (SELECT 1 FROM ProductPackage k2 JOIN k2.extraProfessions ep WHERE k2 = k AND ep.id = :professionId))
            """)
    Page<ProductPackage> searchAdmin(@Param("q") String q, @Param("professionId") UUID professionId, Pageable pageable);

    /** [profession id, packages whose main trade it is] for every trade that has any. */
    @Query("SELECT k.profession.id, COUNT(k) FROM ProductPackage k GROUP BY k.profession.id")
    List<Object[]> countByMainProfession();

    /** [profession id, packages also listed under it] for every trade that has any. */
    @Query("SELECT ep.id, COUNT(k) FROM ProductPackage k JOIN k.extraProfessions ep GROUP BY ep.id")
    List<Object[]> countByExtraProfession();
}
