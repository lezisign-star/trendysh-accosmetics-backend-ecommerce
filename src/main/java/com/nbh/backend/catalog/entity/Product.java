package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The template. NOT sellable and NOT stocked directly — every sellable, stockable
 * thing is a {@link ProductVariant}. A product with no variants cannot be added to
 * a cart or a stock document; that constraint is enforced in the service layer.
 */
@Getter
@Setter
@jakarta.persistence.EntityListeners(com.nbh.backend.live.EntityChangeListener.class)
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

    /**
     * A product can belong to several categories (founder decision 2026-09-24, Phase 3 of the
     * inventory redesign — replaces the old single category/subcategory pair). {@code position} 0
     * is the "primary" category, used for breadcrumbs and the SKU prefix; {@code @OrderColumn}
     * lets Hibernate own that column, which means every save replaces the whole join-table row set
     * for this product rather than diffing it — fine at this catalogue's size, revisit only if it
     * ever becomes a hot path. The old {@code product.category_id}/{@code subcategory_id} columns
     * still exist in the database (V27 kept them for rollback safety) but are deliberately not
     * mapped here — nothing in Phase 3a's code reads or writes them; V28 drops them for good once
     * Phase 3e is live and re-verified.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "product_category",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    @OrderColumn(name = "position")
    private List<Category> categories = new ArrayList<>();

    @Column(length = 500)
    private String shortDescription;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String ingredients;

    @Column(name = "how_to_use", columnDefinition = "TEXT")
    private String howToUse;

    /** The product page's bullet list, one highlight per line; null when there are none. */
    @Column(columnDefinition = "TEXT")
    private String highlights;

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

    /** Developer-toggleable demo content (2026-09-29) — see MockDataSeederService. Bulk-flipping
     *  isPublished on every row where this is true is the entire enable/disable mechanism; nothing
     *  else in the codebase needs to know this flag exists. */
    @Column(nullable = false)
    private boolean isMockData = false;
}
