package com.nbh.backend.variantjobs;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** One "Generate" from the variant wizard, worked through in the background (V64, VARIANTS_PLAN.md 3.3). */
@Getter
@Setter
@Entity
@Table(name = "variant_job")
public class VariantJob extends BaseEntity {

    public enum Status {
        QUEUED, RUNNING, PAUSED_QUOTA, AWAITING_APPROVAL, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED;

        /** Still has work to do (or will, once the quota allows). */
        public boolean active() {
            return this == QUEUED || this == RUNNING || this == PAUSED_QUOTA;
        }
    }

    /** How far the job got; each stage only does the items not already done, so a restart picks up where it stopped. */
    public enum Stage { CREATE, DESCRIPTIONS, PHOTOS, DONE }

    public static final Set<Status> FINISHED = EnumSet.of(Status.AWAITING_APPROVAL, Status.COMPLETED,
            Status.COMPLETED_WITH_ERRORS, Status.FAILED, Status.CANCELLED);

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Stage stage = Stage.CREATE;

    @Column(name = "approve_first", nullable = false)
    private boolean approveFirst;

    /** The checked wizard body ({@link VariantJobRequest}) as JSON. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String request;

    /** The plain-words summary staff confirmed ("Prices: …; Exceptions: …"), one line each. */
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "locked_by", length = 80)
    private String lockedBy;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "variants_total", nullable = false)
    private int variantsTotal;

    @Column(name = "variants_created", nullable = false)
    private int variantsCreated;

    @Column(name = "descriptions_done", nullable = false)
    private int descriptionsDone;

    @Column(name = "descriptions_failed", nullable = false)
    private int descriptionsFailed;

    @Column(name = "photos_done", nullable = false)
    private int photosDone;

    @Column(name = "photos_failed", nullable = false)
    private int photosFailed;
}
