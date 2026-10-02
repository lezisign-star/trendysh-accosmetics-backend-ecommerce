package com.nbh.backend.delivery;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryPlaceRepository extends JpaRepository<DeliveryPlace, UUID> {

    @EntityGraph(attributePaths = "prices")
    @Query("""
            SELECT DISTINCT p FROM DeliveryPlace p
            WHERE (:activeOnly = false OR p.active = true)
              AND (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(COALESCE(p.region, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
            ORDER BY p.name ASC
            """)
    List<DeliveryPlace> search(@Param("q") String q, @Param("activeOnly") boolean activeOnly);

    @EntityGraph(attributePaths = "prices")
    Optional<DeliveryPlace> findWithPricesById(UUID id);

    boolean existsByNameIgnoreCase(String name);
}
