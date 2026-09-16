package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "product_attribute_value", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"attribute_id", "value"})
})
public class ProductAttributeValue extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", nullable = false)
    private ProductAttribute attribute;

    @Column(nullable = false, length = 100)
    private String value;

    @Column(length = 150)
    private String displayValue;

    @Column(length = 7)
    private String swatchHex;

    @Column(length = 500)
    private String swatchImageUrl;

    @Column(nullable = false)
    private int sortOrder = 0;
}
