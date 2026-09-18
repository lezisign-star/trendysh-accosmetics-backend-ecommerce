package com.nbh.backend.catalog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per storefront product-page view — the "clicks" figure the founder
 * asked to track. {@code variant} is nullable: a view can happen before any
 * specific shade/size is chosen. Deliberately not extending
 * {@link com.nbh.backend.common.entity.BaseEntity} — an append-only fact,
 * never updated.
 */
@Getter
@Setter
@Entity
@Table(name = "product_view_event")
public class ProductViewEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
