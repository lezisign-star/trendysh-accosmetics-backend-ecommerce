package com.nbh.backend.kits.entity;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** One real product variant + quantity that makes up a {@link Kit}. */
@Getter
@Setter
@Entity
@Table(name = "kit_component")
public class KitComponent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kit_id", nullable = false)
    private Kit kit;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_variant_id", nullable = false)
    private ProductVariant componentVariant;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    /**
     * Whether a customer may pick another variant (colour or size) of this product inside the kit; the product itself
     * and its quantity never change. On unless staff turn it off for this product.
     */
    @Column(name = "customer_can_change", nullable = false)
    private boolean customerCanChange = true;
}
