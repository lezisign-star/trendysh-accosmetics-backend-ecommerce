package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One site-wide percent-off campaign — e.g. "Weekend Sale, 20% off, ends Sunday 6pm". At most one
 * is ever "currently active" ({@link #isCurrentlyActive}); enforced by
 * {@code PromoCampaignService}, not a DB constraint, so a conflict gets a clear error message.
 * Rows are never deleted, so past campaigns stay as history — a lapsed one is just left with
 * {@code endsAt} in the past, or {@code isActive} flipped off as a kill-switch.
 */
@Getter
@Setter
@Entity
@Table(name = "promo_campaign")
public class PromoCampaign extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "percent_off", nullable = false, precision = 5, scale = 2)
    private BigDecimal percentOff;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    public boolean isCurrentlyActive(Instant now) {
        return isActive && !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
}
