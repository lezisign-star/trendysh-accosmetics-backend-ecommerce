package com.nbh.backend.delivery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryPlaceSuggestionRepository extends JpaRepository<DeliveryPlaceSuggestion, UUID> {

    List<DeliveryPlaceSuggestion> findByStatusOrderByCreatedAtAsc(DeliveryPlaceSuggestion.Status status);

    long countByStatus(DeliveryPlaceSuggestion.Status status);

    boolean existsByStatusAndNameIgnoreCase(DeliveryPlaceSuggestion.Status status, String name);
}
