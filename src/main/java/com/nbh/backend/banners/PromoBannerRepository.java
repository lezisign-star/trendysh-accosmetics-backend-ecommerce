package com.nbh.backend.banners;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PromoBannerRepository extends JpaRepository<PromoBanner, UUID> {

    List<PromoBanner> findAllByOrderByUpdatedAtDesc();

    /** Approved and inside its dates now. */
    @Query("SELECT b FROM PromoBanner b WHERE b.status = com.nbh.backend.banners.PromoBanner.Status.APPROVED"
            + " AND b.startsAt <= :now AND b.endsAt > :now ORDER BY b.sortOrder, b.startsAt")
    List<PromoBanner> findLive(Instant now);
}
