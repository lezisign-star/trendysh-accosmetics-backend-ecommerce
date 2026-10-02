package com.nbh.backend.imagecleanup;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** One generated image: the new product_image row, the original photo it was made from, and where it lives on Cloudinary. */
@Getter
@Setter
@Entity
@Table(name = "image_cleanup_item")
public class ImageCleanupItem extends BaseEntity {

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_image_id", nullable = false)
    private UUID productImageId;

    @Column(name = "reference_image_id", nullable = false)
    private UUID referenceImageId;

    @Column(name = "cloudinary_public_id", length = 255)
    private String cloudinaryPublicId;

    @Column(name = "prompt_used", columnDefinition = "TEXT")
    private String promptUsed;
}
