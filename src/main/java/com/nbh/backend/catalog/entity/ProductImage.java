package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@jakarta.persistence.EntityListeners(com.nbh.backend.live.EntityChangeListener.class)
@Entity
@Table(name = "product_image")
public class ProductImage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Null = applies to the whole product; set = a shade/variant-specific shot. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(length = 500)
    private String thumbnailUrl;

    @Column(length = 250)
    private String altText;

    @Column(nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false)
    private boolean isPrimary = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImageSource source = ImageSource.UPLOAD;

    /**
     * Where the part that changes sits on this photo, "x% y%" (V66), so the shop's cards crop around it (the bottle cap
     * of a gel polish rather than its middle). Set by a variant job from the part staff marked; null = the middle.
     */
    @Column(length = 16)
    private String focus;

    private Integer width;
    private Integer height;
    private Integer byteSize;

    /** Cut to the column's length: a very long product plus option name must not make the save fail. */
    public void setAltText(String altText) {
        this.altText = altText != null && altText.length() > ALT_TEXT_MAX ? altText.substring(0, ALT_TEXT_MAX) : altText;
    }

    private static final int ALT_TEXT_MAX = 250;

    public enum ImageSource { UPLOAD, CAMERA_CAPTURE, AI_CLEANED }
}
