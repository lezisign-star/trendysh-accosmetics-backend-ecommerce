package com.nbh.backend.dealphotos;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A promotional picture of a product on a flash deal, made with Gemini from its real photo (owner, 2026-10-24: "such
 * promotional images of a product can be generated once a product is in its deal phase"). Kept apart from the product's
 * own photos, so it never shows in its gallery; the shop shows the APPROVED one on deal cards while the deal is live.
 */
@Getter
@Setter
@Entity
@Table(name = "deal_photo")
public class DealPhoto extends BaseEntity {

    public enum Status { PENDING, APPROVED, REJECTED, FAILED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    /** The picture in Cloudinary; null for a FAILED attempt. */
    @Column(columnDefinition = "TEXT")
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** Why an attempt FAILED (Gemini's own words), for staff. */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;
}
