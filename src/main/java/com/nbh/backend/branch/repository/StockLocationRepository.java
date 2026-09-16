package com.nbh.backend.branch.repository;

import com.nbh.backend.branch.entity.LocationType;
import com.nbh.backend.branch.entity.StockLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockLocationRepository extends JpaRepository<StockLocation, UUID> {
    Optional<StockLocation> findByCode(String code);

    Optional<StockLocation> findByBranchIdAndLocationType(UUID branchId, LocationType locationType);

    List<StockLocation> findByBranchId(UUID branchId);
}
