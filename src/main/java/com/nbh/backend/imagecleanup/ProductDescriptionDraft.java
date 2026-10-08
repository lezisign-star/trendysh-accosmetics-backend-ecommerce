package com.nbh.backend.imagecleanup;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * AI-written text for a product waiting for a person to approve it. Nothing in the shop reads this table: approving a
 * draft copies it onto the product, rejecting it deletes it.
 */
@Getter
@Setter
@Entity
@Table(name = "product_description_draft")
public class ProductDescriptionDraft extends BaseEntity {

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "short_description", length = 500)
    private String shortDescription;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** One highlight per line. */
    @Column(columnDefinition = "TEXT")
    private String highlights;

    @Column(columnDefinition = "TEXT")
    private String ingredients;

    @Column(name = "how_to_use", columnDefinition = "TEXT")
    private String howToUse;

    @Column(name = "seo_title", length = 200)
    private String seoTitle;

    @Column(name = "seo_description", length = 500)
    private String seoDescription;
}
