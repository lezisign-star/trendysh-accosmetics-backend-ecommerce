package com.nbh.backend.variantjobs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VariantJobItemRepository extends JpaRepository<VariantJobItem, UUID> {

    List<VariantJobItem> findByJobIdOrderByPositionAsc(UUID jobId);

    List<VariantJobItem> findByJobIdInOrderByPositionAsc(Collection<UUID> jobIds);
}
