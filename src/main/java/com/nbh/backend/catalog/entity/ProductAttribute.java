package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** A dimension a variant can differ by — Shade, Size, Scent, Finish. */
@Getter
@Setter
@Entity
@Table(name = "product_attribute")
public class ProductAttribute extends BaseEntity {

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "display_type", nullable = false, length = 20)
    private AttributeDisplayType displayType;

    @Column(nullable = false)
    private int sortOrder = 0;

    public enum AttributeDisplayType { SWATCH, TEXT, DROPDOWN }
}
