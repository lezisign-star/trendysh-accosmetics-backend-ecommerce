package com.nbh.backend.imagecleanup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ImageCleanupItemRepository extends JpaRepository<ImageCleanupItem, UUID> {

    List<ImageCleanupItem> findByBatchId(UUID batchId);
}
