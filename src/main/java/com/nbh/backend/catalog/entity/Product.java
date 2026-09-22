package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The template. NOT sellable and NOT stocked directly — every sellable, stockable
 * thing is a {@link ProductVariant}. A product with no variants cannot be added to
 * a cart or a stock document; that constraint is enforced in the service layer.
 */
@Getter
@Setter
@Entity
@Table(name = "product")
public class Product extends BaseEntity {

    @Column(nullable = false, unique = true, length = 180)
    private String slug;

    @Column(nullable = false, length = 250)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subcategory_id")
    private Category subcategory;

    @Column(length = 500)
    private String shortDescription;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String ingredients;

    @Column(name = "how_to_use", columnDefinition = "TEXT")
    private String howToUse;

    @Column(nullable = false)
    private boolean isStockItem = true;

    @Column(nullable = false)
    private boolean isPublished = false;

    private Instant publishedAt;

    @Column(nullable = false)
    private boolean deleted = false;

    /** Manual merchandising pin — staff-controlled, no automatic ranking involved on its own. */
    @Column(name = "is_popular", nullable = false)
    private boolean isPopular = false;

    /** Manual flag, no auto-expiry by design (founder decision 2026-09-22) — staff turn it off themselves. */
    @Column(name = "is_new_arrival", nullable = false)
    private boolean isNewArrival = false;

    @Column(length = 200)
    private String seoTitle;

    @Column(length = 500)
    private String seoDescription;

    private UUID createdBy;

    private UUID updatedBy;
}
