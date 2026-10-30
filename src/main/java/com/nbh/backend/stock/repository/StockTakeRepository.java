package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.entity.StockTake;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StockTakeRepository extends JpaRepository<StockTake, UUID> {
    Optional<StockTake> findFirstByStatusOrderByCreatedAtDesc(String status);

    Optional<StockTake> findFirstByOrderByCreatedAtDesc();
}
