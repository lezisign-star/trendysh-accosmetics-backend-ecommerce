package com.nbh.backend.banners;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OccasionRepository extends JpaRepository<Occasion, UUID> {
    List<Occasion> findAllByOrderByPriorityDescNameAsc();

    boolean existsByCode(String code);
}
