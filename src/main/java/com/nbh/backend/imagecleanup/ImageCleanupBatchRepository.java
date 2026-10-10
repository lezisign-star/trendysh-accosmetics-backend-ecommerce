package com.nbh.backend.imagecleanup;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ImageCleanupBatchRepository extends JpaRepository<ImageCleanupBatch, UUID> {

    boolean existsByStatus(ImageCleanupBatch.Status status);

    List<ImageCleanupBatch> findByStatus(ImageCleanupBatch.Status status);

    @Query("SELECT b FROM ImageCleanupBatch b ORDER BY b.createdAt DESC, b.id DESC")
    List<ImageCleanupBatch> recent(Pageable pageable);
}
