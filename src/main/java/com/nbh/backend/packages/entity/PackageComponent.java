package com.nbh.backend.packages.entity;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** One real product variant + quantity that makes up a {@link ProductPackage}. */
@Getter
@Setter
@Entity
@Table(name = "package_component")
public class PackageComponent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "package_id", nullable = false)
    private ProductPackage productPackage;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_variant_id", nullable = false)
    private ProductVariant componentVariant;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    /**
     * Whether a customer may pick another variant (colour or size) of this product inside the package; the product itself
     * and its quantity never change. Off unless staff switch it on for this product (owner, 2026-10-23).
     */
    @Column(name = "customer_can_change", nullable = false)
    private boolean customerCanChange = false;
}
