package com.nbh.backend.imagecleanup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ImageDraftRepository extends JpaRepository<ImageDraft, UUID> {

    List<ImageDraft> findAllByOrderByCreatedAtDesc();

    List<ImageDraft> findByProductIdOrderByCreatedAtDesc(UUID productId);

    List<ImageDraft> findByVariantIdOrderByCreatedAtDesc(UUID variantId);

    List<ImageDraft> findByBatchId(UUID batchId);

    long countByBatchId(UUID batchId);

    /** True while a draft still points at this exact file, so Cloudinary keeps it. */
    boolean existsByUrl(String url);

    List<ImageDraft> findByCreatedAtBefore(Instant cutoff);

    /** How many drafts each of these products has waiting, for the staff product list. */
    @Query("SELECT d.productId AS productId, COUNT(d) AS drafts FROM ImageDraft d WHERE d.productId IN :ids GROUP BY d.productId")
    List<DraftCount> countByProducts(@Param("ids") Collection<UUID> productIds);

    interface DraftCount {
        UUID getProductId();

        long getDrafts();
    }

    /** How many drafts each batch still has waiting, for the batch list. */
    @Query("SELECT d.batchId AS batchId, COUNT(d) AS drafts FROM ImageDraft d GROUP BY d.batchId")
    List<BatchDraftCount> countByBatch();

    interface BatchDraftCount {
        UUID getBatchId();

        long getDrafts();
    }
}
