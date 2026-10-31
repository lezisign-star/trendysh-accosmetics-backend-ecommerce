package com.nbh.backend.banners;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** A picture a {@link PromoBanner} could have (V86): made by Gemini, uploaded by staff, or one of its products' photos. */
@Getter
@Setter
@Entity
@Table(name = "promo_banner_picture")
public class PromoBannerPicture extends BaseEntity {

    public enum Source { AI, UPLOAD, PRODUCT }

    public enum Status { MAKING, READY, FAILED }

    @Column(name = "banner_id", nullable = false)
    private UUID bannerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Status status;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(columnDefinition = "TEXT")
    private String brief;

    @Column(columnDefinition = "TEXT")
    private String note;
}
