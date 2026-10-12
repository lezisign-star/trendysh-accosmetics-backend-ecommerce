package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One colour or one size a product comes in (V63, VARIANTS_PLAN.md 3.1), in the order the shop shows them. Kept in step
 * with the product's variants by {@code ProductOptionsService.sync}: a value exists here while at least one of the
 * product's (not removed) variants has it.
 */
@Getter
@Setter
@Entity
@Table(name = "product_option_value")
public class ProductOptionValue extends BaseEntity {

    public enum Kind { COLOR, SIZE }

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(nullable = false, length = 80)
    private String value;

    @Column(nullable = false)
    private int position;

    /** COLOR only: the dot, "#RRGGBB". */
    @Column(name = "swatch_hex", length = 9)
    private String swatchHex;

    /** COLOR only: staff's choice of {@link ColorFamily} by name; null = worked out from the dot. */
    @Column(name = "color_family", length = 20)
    private String colorFamily;

    /** SIZE only: what that size's packaging says ("100ml"), for the generator's photos. */
    @Column(name = "size_label_text", length = 60)
    private String sizeLabelText;

    /** SIZE only: a photo of that size (a {@code product_image} id), for the generator's photos. */
    @Column(name = "size_photo_image_id")
    private UUID sizePhotoImageId;

    /** Staff's family, else the one the dot looks like; null when neither is known. */
    public ColorFamily effectiveColorFamily() {
        ColorFamily chosen = ColorFamily.ofStored(colorFamily);
        return chosen != null ? chosen : ColorFamily.fromHex(swatchHex);
    }
}
