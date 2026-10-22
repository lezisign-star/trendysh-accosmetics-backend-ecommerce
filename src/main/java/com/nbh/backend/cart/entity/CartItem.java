package com.nbh.backend.cart.entity;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "cart_item", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"cart_id", "variant_id", "choice_key"})
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

    /**
     * What the shopper picked inside a kit, keyed by the DEFAULT variant of the product they changed (a kit's component
     * rows are replaced on every save, but a product is in a kit once, so its default variant is a stable key) and
     * holding the variant they chose instead. Only picks that differ from the default are stored; empty for a plain
     * line and for a kit taken as it comes.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "cart_item_choice", joinColumns = @JoinColumn(name = "cart_item_id"))
    @MapKeyColumn(name = "default_variant_id")
    @Column(name = "chosen_variant_id")
    private Map<UUID, UUID> choices = new HashMap<>();

    /**
     * Identifies the picks so that the same kit with different picks is a different line: "" for none, else a digest of
     * the sorted picks (see KitChoices.keyOf). Part of the line's uniqueness together with the cart and the variant.
     */
    @Column(name = "choice_key", nullable = false, length = 64)
    private String choiceKey = "";

    @Transient
    public BigDecimal getLineTotal() {
        return unitPriceSnapshot.multiply(qty);
    }
}
