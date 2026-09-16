package com.nbh.backend.stock.entity;

import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A hold against future availability — an online cart/order hold and a POS
 * ticket both create one of these, so neither channel can oversell a unit
 * the other one is mid-way through selling. See
 * {@link com.nbh.backend.stock.service.StockReservationService} for the
 * locking protocol that makes this race-safe.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_reservation")
public class StockReservation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private StockLocation location;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal fulfilledQty = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal releasedQty = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationSource source;

    @Column(nullable = false, length = 40)
    private String referenceType;

    @Column(nullable = false)
    private UUID referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReservationStatus status = ReservationStatus.ACTIVE;

    private Instant expiresAt;

    private UUID createdByUserId;

    public enum ReservationSource { ONLINE_CART, ONLINE_ORDER, POS_TICKET, TRANSFER_OUT, MANUAL_HOLD }

    public enum ReservationStatus { ACTIVE, FULFILLED, RELEASED, EXPIRED }

    @Transient
    public BigDecimal getOutstandingQty() {
        return qty.subtract(fulfilledQty).subtract(releasedQty);
    }
}
