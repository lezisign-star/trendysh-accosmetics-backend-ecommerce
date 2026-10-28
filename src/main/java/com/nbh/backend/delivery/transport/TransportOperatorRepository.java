package com.nbh.backend.delivery.transport;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransportOperatorRepository extends JpaRepository<TransportOperator, UUID> {

    /** What the checkout lists: the active ones, the most chosen first, then staff's order. */
    @Query("select o from TransportOperator o where o.active = true order by o.picks desc, o.sortOrder asc, o.name asc")
    List<TransportOperator> activeForShoppers();

    @Query("select o from TransportOperator o order by o.sortOrder asc, o.name asc")
    List<TransportOperator> allForStaff();

    Optional<TransportOperator> findByNameIgnoreCase(String name);

    @Modifying
    @Query("update TransportOperator o set o.picks = o.picks + 1 where o.id = :id")
    void countPick(@Param("id") UUID id);
}
