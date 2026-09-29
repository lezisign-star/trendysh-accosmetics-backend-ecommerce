package com.nbh.backend.tumaboda;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommonDeliveryAreaRepository extends JpaRepository<CommonDeliveryArea, UUID> {

    List<CommonDeliveryArea> findAllByActiveTrue();

    Optional<CommonDeliveryArea> findByIdAndActiveTrue(UUID id);
}
