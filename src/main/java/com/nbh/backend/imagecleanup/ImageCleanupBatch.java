package com.nbh.backend.imagecleanup;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One run of the AI photo job. Its {@link ImageCleanupItem}s let everything it published be undone at once. */
@Getter
@Setter
@Entity
@Table(name = "image_cleanup_batch")
public class ImageCleanupBatch extends BaseEntity {

    public enum Status {
        IN_PROGRESS, COMPLETED, COMPLETED_WITH_ERRORS,
        /** Gemini itself refused calls for quota/rate-limit reasons, so the rest of the batch was not attempted. */
        STOPPED_GEMINI_QUOTA,
        /** Gemini is not configured (no API key) — nothing was attempted. */
        FAILED,
        /** The app restarted mid-run; the background job died with it. */
        INTERRUPTED,
        /** Undone: generated images removed, original photos restored. */
        DELETED
    }

    /** What happens to the photos a run makes. */
    public enum PublishMode {
        /** Straight into the shop, placed first (how every run worked before staff could review). */
        IMMEDIATE,
        /** Held as drafts nobody in the shop can see until a person approves each one. The default. */
        REVIEW
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.IN_PROGRESS;

    @Enumerated(EnumType.STRING)
    @Column(name = "publish_mode", nullable = false, length = 10)
    private PublishMode publishMode = PublishMode.IMMEDIATE;

    /** Comma-separated {@link PhotoView} keys that were asked for; null on runs from before views could be chosen. */
    @Column(name = "views", length = 300)
    private String views;

    /** The staff note added to every photo of the run, if any. */
    @Column(name = "note", length = 600)
    private String note;

    /** Whether this run also drafted descriptions for products that had none. */
    @Column(name = "write_descriptions", nullable = false)
    private boolean writeDescriptions;

    @Column(name = "descriptions_drafted", nullable = false)
    private int descriptionsDrafted;

    @Column(name = "images_per_product", nullable = false)
    private int imagesPerProduct;

    @Column(name = "requested_count", nullable = false)
    private int requestedCount;

    @Column(name = "succeeded_count", nullable = false)
    private int succeededCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "last_error", length = 600)
    private String lastError;

    @Column(name = "triggered_by_user_id")
    private UUID triggeredByUserId;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
