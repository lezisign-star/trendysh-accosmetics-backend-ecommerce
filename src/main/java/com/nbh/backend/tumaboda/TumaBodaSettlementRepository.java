package com.nbh.backend.tumaboda;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TumaBodaSettlementRepository extends JpaRepository<TumaBodaSettlement, UUID> {

    List<TumaBodaSettlement> findAllByOrderBySettledAtDescCreatedAtDesc();
}
