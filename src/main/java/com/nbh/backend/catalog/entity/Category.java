package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@jakarta.persistence.EntityListeners(com.nbh.backend.live.EntityChangeListener.class)
@Entity
@Table(name = "category")
public class Category extends BaseEntity {

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(nullable = false, length = 150)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false)
    private boolean isPublished = true;

    /** Its products' options with no stock number yet (its subcategories' too) can be ordered without a stock limit (V78). */
    @Column(name = "sell_without_stock", nullable = false)
    private boolean sellWithoutStock = false;

    /** Developer-toggleable demo content (2026-09-29) — see MockDataSeederService. */
    @Column(nullable = false)
    private boolean isMockData = false;
}
