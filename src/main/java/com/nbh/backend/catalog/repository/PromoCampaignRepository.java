package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.PromoCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PromoCampaignRepository extends JpaRepository<PromoCampaign, java.util.UUID> {

    /** At most one row should ever match — {@code PromoCampaignService} enforces that on write. */
    @Query("SELECT c FROM PromoCampaign c WHERE c.isActive = true AND :now >= c.startsAt AND :now < c.endsAt")
    Optional<PromoCampaign> findCurrentlyActive(@Param("now") Instant now);

    List<PromoCampaign> findAllByOrderByStartsAtDesc();
}
