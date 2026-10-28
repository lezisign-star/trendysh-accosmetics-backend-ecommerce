package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.entity.StockShortfallEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StockShortfallEventRepository extends JpaRepository<StockShortfallEvent, UUID> {

    /** Not handled yet, newest first: the critical banner. */
    @Query("select e from StockShortfallEvent e where e.handledAt is null order by e.createdAt desc")
    List<StockShortfallEvent> unhandled(Pageable page);

    long countByHandledAtIsNull();

    /** One shopper trying the same thing again within the window is the same event. */
    boolean existsByVariantIdAndSessionRefAndCreatedAtAfter(UUID variantId, String sessionRef, Instant after);

    /** What shoppers were refused for these options since a moment, newest first (the Restock screen). */
    List<StockShortfallEvent> findByVariantIdInAndCreatedAtAfterOrderByCreatedAtDesc(Collection<UUID> variantIds,
                                                                                     Instant after);

    /** The options shoppers were refused for since a moment (the Restock screen lists them even when stock says fine). */
    @Query("select distinct e.variantId from StockShortfallEvent e where e.createdAt > :after")
    List<UUID> variantsRefusedSince(@Param("after") Instant after);

    @Modifying
    @Query("update StockShortfallEvent e set e.handledAt = :now, e.handledBy = :by, e.updatedAt = :now "
            + "where e.handledAt is null and e.createdAt <= :upTo")
    int handleAllUpTo(@Param("upTo") Instant upTo, @Param("now") Instant now, @Param("by") String by);
}
