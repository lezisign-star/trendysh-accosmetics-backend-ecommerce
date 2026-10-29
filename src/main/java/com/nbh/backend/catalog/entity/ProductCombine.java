package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One combine of products into a main product (V83, owner 2026-10-29), with what it changed so it can be split back.
 * See {@code ProductCombineService}.
 */
@Getter
@Setter
@Entity
@Table(name = "product_combine")
public class ProductCombine extends BaseEntity {

    @Column(name = "main_product_id", nullable = false)
    private UUID mainProductId;

    @Column(name = "combined_by", length = 200)
    private String combinedBy;

    /** {@code ProductCombineService.Snapshot} as JSON: the main and each joined product as they were before. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshot;

    /** The joined products' names, one per line, for the main's page. */
    @Column(name = "joined_names", nullable = false, columnDefinition = "TEXT")
    private String joinedNames;

    @Column(name = "split_at")
    private Instant splitAt;

    @Column(name = "split_by", length = 200)
    private String splitBy;
}
