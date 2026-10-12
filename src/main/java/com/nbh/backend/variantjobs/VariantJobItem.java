package com.nbh.backend.variantjobs;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One variant a {@link VariantJob} makes (V64). */
@Getter
@Setter
@Entity
@Table(name = "variant_job_item")
public class VariantJobItem extends BaseEntity {

    public enum StepStatus { PENDING, DONE, FAILED, SKIPPED }

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private int position;

    /** Set once the variant exists. */
    @Column(name = "variant_id")
    private UUID variantId;

    @Column(length = 80)
    private String color;

    @Column(length = 80)
    private String size;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "opening_stock", precision = 14, scale = 3)
    private BigDecimal openingStock;

    @Column(name = "unit_cost", precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "description_status", nullable = false, length = 12)
    private StepStatus descriptionStatus = StepStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_status", nullable = false, length = 12)
    private StepStatus photoStatus = StepStatus.PENDING;

    @Column(name = "photo_attempts", nullable = false)
    private int photoAttempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** "Ruby Red / 50ml", "Ruby Red" or "50ml". */
    public String label() {
        return new com.nbh.backend.variantgen.VariantCombos.Combo(color, size).label();
    }
}
