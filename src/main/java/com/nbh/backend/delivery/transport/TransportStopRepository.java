package com.nbh.backend.delivery.transport;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransportStopRepository extends JpaRepository<TransportStop, UUID> {

    Optional<TransportStop> findByOperatorIdAndNameKey(UUID operatorId, String nameKey);

    /** Suggestions for a shopper: not hidden, matching what they typed so far, the most used first. */
    @Query("""
            select s from TransportStop s
            where s.operator.id = :operatorId and s.hidden = false and s.nameKey like concat('%', :key, '%')
            order by s.uses desc, s.name asc
            """)
    List<TransportStop> suggest(@Param("operatorId") UUID operatorId, @Param("key") String key, Pageable page);

    @Query("select s from TransportStop s where s.operator.id = :operatorId order by s.hidden asc, s.uses desc, s.name asc")
    List<TransportStop> allForStaff(@Param("operatorId") UUID operatorId);

    /**
     * Adds the stop if it is new, in one statement, so two shoppers typing the same new place at once cannot collide on
     * the unique key; the caller then reads it back.
     */
    @Modifying
    @Query(value = """
            INSERT INTO transport_stop (id, operator_id, name, name_key, added_by)
            VALUES (gen_random_uuid(), :operatorId, :name, :nameKey, 'CUSTOMER')
            ON CONFLICT (operator_id, name_key) DO NOTHING
            """, nativeQuery = true)
    int addIfNew(@Param("operatorId") UUID operatorId, @Param("name") String name, @Param("nameKey") String nameKey);

    @Modifying
    @Query("update TransportStop s set s.uses = s.uses + 1 where s.id = :id")
    void countUse(@Param("id") UUID id);
}
