package com.nbh.backend.banners;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One promotional picture made for an occasion and a slot (V84), waiting for staff or picked. See {@link BannerService}. */
@Getter
@Setter
@Entity
@Table(name = "banner")
public class Banner extends BaseEntity {

    public enum Slot { OFFER_CARD, GRID_TILE, CIRCLE }

    public enum Style { FAMILY, HAPPY_PEOPLE, CARTOON_JOKE, ANIMAL, PRODUCT_OFFER, GREETING }

    public enum Status { WAITING, PICKED, REJECTED, RETIRED, FAILED }

    @Column(name = "occasion_id", nullable = false)
    private UUID occasionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Slot slot;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Style style;

    @Column(length = 20)
    private String container;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(length = 90)
    private String line;

    @Column(name = "text_tone", length = 5)
    private String textTone;

    @Column(columnDefinition = "TEXT")
    private String fit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "offer_signature", nullable = false, length = 200)
    private String offerSignature = "";

    @Column(name = "decided_by", length = 200)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;
}
