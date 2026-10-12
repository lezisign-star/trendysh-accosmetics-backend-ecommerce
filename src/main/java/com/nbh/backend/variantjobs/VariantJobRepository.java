package com.nbh.backend.variantjobs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VariantJobRepository extends JpaRepository<VariantJob, UUID> {

    /**
     * The oldest job a worker may take now: a queued one, a paused one whose wait is over, or a running one whose worker
     * stopped saying it is alive (the server restarted). Locked, and skipped by any other claimer that is looking at the
     * same moment, so two workers never take the same job.
     */
    @Query(value = """
            SELECT * FROM variant_job
            WHERE status = 'QUEUED'
               OR (status = 'PAUSED_QUOTA' AND next_attempt_at <= :now)
               OR (status = 'RUNNING' AND (heartbeat_at IS NULL OR heartbeat_at < :staleBefore))
            ORDER BY created_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<VariantJob> claimable(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    /** Locks the product row until the transaction ends, so two jobs for one product make their variants one after the other. */
    @Query(value = "SELECT id FROM product WHERE id = :productId FOR UPDATE", nativeQuery = true)
    UUID lockProduct(@Param("productId") UUID productId);

    List<VariantJob> findByStatusInOrderByCreatedAtDesc(Collection<VariantJob.Status> statuses);

    List<VariantJob> findTop50ByOrderByCreatedAtDesc();

    List<VariantJob> findByProductIdOrderByCreatedAtDesc(UUID productId);
}
