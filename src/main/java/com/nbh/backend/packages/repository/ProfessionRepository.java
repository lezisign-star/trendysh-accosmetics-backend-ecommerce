package com.nbh.backend.packages.repository;

import com.nbh.backend.packages.entity.Profession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfessionRepository extends JpaRepository<Profession, UUID> {

    Optional<Profession> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** Demo professions only while demo data is on (any mock product currently published) — same live signal the analytics screens use. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT pr FROM Profession pr
            WHERE ((pr.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (pr.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            ORDER BY pr.label ASC
            """)
    List<Profession> findAllByOrderByLabelAsc();
}
