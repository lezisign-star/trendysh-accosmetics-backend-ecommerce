package com.nbh.backend.banners;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BannerRepository extends JpaRepository<Banner, UUID> {
    List<Banner> findByStatusInOrderByCreatedAtDesc(Collection<Banner.Status> statuses);

    List<Banner> findByOccasionIdAndStatus(UUID occasionId, Banner.Status status);

    List<Banner> findByOccasionIdAndSlotAndStatusIn(UUID occasionId, Banner.Slot slot, Collection<Banner.Status> statuses);

    long countByStatus(Banner.Status status);
}
