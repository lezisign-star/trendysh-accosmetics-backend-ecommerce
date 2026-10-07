package com.nbh.backend.imagecleanup;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * An AI photo waiting for a person to approve it. Nothing in the shop reads this table, so a draft can never reach
 * a shopper: approving one creates the ordinary {@code product_image}, rejecting one deletes it and its file.
 */
@Getter
@Setter
@Entity
@Table(name = "image_draft")
public class ImageDraft extends BaseEntity {

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** The option the photo is for — and will belong to once approved. */
    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    /** The real photo it was made from. */
    @Column(name = "reference_image_id", nullable = false)
    private UUID referenceImageId;

    @Column(name = "view_key", nullable = false, length = 30)
    private String viewKey;

    @Column(length = 600)
    private String note;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "cloudinary_public_id", length = 255)
    private String cloudinaryPublicId;

    @Column(name = "prompt_used", columnDefinition = "TEXT")
    private String promptUsed;
}
