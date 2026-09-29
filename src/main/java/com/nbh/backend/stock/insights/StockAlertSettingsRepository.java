package com.nbh.backend.stock.insights;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StockAlertSettingsRepository extends JpaRepository<StockAlertSettings, UUID> {

    Optional<StockAlertSettings> findFirstByOrderByCreatedAtAsc();
}
