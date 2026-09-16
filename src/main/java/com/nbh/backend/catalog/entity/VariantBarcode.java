package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "variant_barcode")
public class VariantBarcode extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(nullable = false, unique = true, length = 64)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(name = "barcode_type", nullable = false, length = 20)
    private BarcodeType barcodeType;

    @Column(nullable = false)
    private boolean isPrimary = false;

    public enum BarcodeType { EAN13, UPC, CODE128, INTERNAL }
}
