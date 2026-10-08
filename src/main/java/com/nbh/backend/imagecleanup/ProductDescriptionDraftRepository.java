package com.nbh.backend.imagecleanup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductDescriptionDraftRepository extends JpaRepository<ProductDescriptionDraft, UUID> {
    List<ProductDescriptionDraft> findAllByOrderByCreatedAtDesc();
    List<ProductDescriptionDraft> findByBatchIdOrderByCreatedAtAsc(UUID batchId);
    Optional<ProductDescriptionDraft> findByProductId(UUID productId);
    long countByBatchId(UUID batchId);
    void deleteByBatchId(UUID batchId);

    /** How many descriptions each batch still has waiting, for the batch list. */
    @Query("SELECT d.batchId AS batchId, COUNT(d) AS drafts FROM ProductDescriptionDraft d WHERE d.batchId IS NOT NULL GROUP BY d.batchId")
    List<BatchCount> countByBatch();

    interface BatchCount {
        UUID getBatchId();
        long getDrafts();
    }
}
