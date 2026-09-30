package com.nbh.backend.cart.entity;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "cart_item", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"cart_id", "variant_id"})
})
public class CartItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    /** Snapshot at time of adding — the price shown in the cart never silently changes underfoot. */
    @Column(name = "unit_price_snapshot", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPriceSnapshot;

    /** Whether {@link #unitPriceSnapshot} is currently the variant's wholesale price — see EffectivePriceResolver. */
    @Column(name = "wholesale_applied", nullable = false)
    private boolean wholesaleApplied = false;

    @Column(name = "product_name_snapshot", nullable = false, length = 250)
    private String productNameSnapshot;

    @Column(name = "variant_name_snapshot", nullable = false, length = 250)
    private String variantNameSnapshot;

    @Column(name = "added_at", nullable = false)
    private java.time.Instant addedAt = java.time.Instant.now();

    @Transient
    public BigDecimal getLineTotal() {
        return unitPriceSnapshot.multiply(qty);
    }
}
