package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
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

    /** Developer-toggleable demo content (2026-09-29) — see MockDataSeederService. */
    @Column(nullable = false)
    private boolean isMockData = false;
}
