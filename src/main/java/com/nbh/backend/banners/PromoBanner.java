package com.nbh.backend.banners;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One banner staff built on purpose (V86): where it shows, what it says and links to, its picture and dates. See {@link PromoBannerService}. */
@Getter
@Setter
@Entity
@Table(name = "promo_banner")
public class PromoBanner extends BaseEntity {

    public enum Placement {
        /** The big rotating banner at the top of the home page. */
        HERO,
        /** A wide, thin band across the home page or a category's pages. */
        STRIP,
        /** An advert-like tile on a category's pages. */
        TILE,
        /** The first card of "On offer today" (a V84 slot). */
        OFFER_CARD,
        /** One tile in sixteen in product grids (a V84 slot). */
        GRID_TILE,
        /** A category's circle on the home page (a V84 slot). */
        CIRCLE;

        /** The slots the shop already draws, served with the occasion banners. */
        boolean isSmallSlot() {
            return this == OFFER_CARD || this == GRID_TILE || this == CIRCLE;
        }
    }

    public enum LinkType { DEALS, CATEGORY, PRODUCT }

    public enum OfferMode { AUTO, CUSTOM, NONE }

    public enum Status { DRAFT, APPROVED, PAUSED }

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Placement placement;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 10)
    private LinkType linkType = LinkType.DEALS;

    @Column(name = "link_category_id")
    private UUID linkCategoryId;

    @Column(name = "link_variant_id")
    private UUID linkVariantId;

    @Column(length = 80)
    private String headline;

    @Column(length = 140)
    private String subline;

    @Column(name = "cta_label", length = 30)
    private String ctaLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "offer_mode", nullable = false, length = 6)
    private OfferMode offerMode = OfferMode.AUTO;

    @Column(name = "offer_text", length = 40)
    private String offerText;

    @Column(name = "show_countdown", nullable = false)
    private boolean showCountdown;

    @Column(name = "occasion_id")
    private UUID occasionId;

    @Column(name = "text_tone", nullable = false, length = 5)
    private String textTone = "light";

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "focus_x", nullable = false)
    private short focusX = 50;

    @Column(name = "focus_y", nullable = false)
    private short focusY = 50;

    /** Staff's advanced look as JSON (V87), or null for the automatic one. See {@link PromoBannerService.Style}. */
    @Column(columnDefinition = "TEXT")
    private String style;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.DRAFT;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "approved_by", length = 200)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;
}
