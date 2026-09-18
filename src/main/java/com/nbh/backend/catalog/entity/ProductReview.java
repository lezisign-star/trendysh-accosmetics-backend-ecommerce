package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import com.nbh.backend.order.entity.CustomerOrder;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A verified-purchase review: eligibility is checked against a real {@link CustomerOrder}
 * (order number + the phone it was placed with), since checkout is guest-only (architecture
 * doc §4.7) — there is no customer login to attach a review to. PENDING until a staff member
 * moderates it; only APPROVED reviews are ever shown on the storefront.
 */
@Getter
@Setter
@Entity
@Table(name = "product_review")
public class ProductReview extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Column(nullable = false)
    private int rating;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "reviewer_name", nullable = false, length = 150)
    private String reviewerName;

    @Column(name = "reviewer_phone", nullable = false, length = 20)
    private String reviewerPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(name = "moderated_by")
    private UUID moderatedBy;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    public enum ReviewStatus { PENDING, APPROVED, REJECTED }
}
