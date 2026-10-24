package com.nbh.backend.kits.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A customer-facing trade/profession a {@link Kit} is curated for — "Nail Tech", "Lash Tech", and
 * so on. Several kits can share one profession (a beginner and a pro tier, say); each kit carries
 * its own {@code description} to tell them apart. Added inline from the kit form, and kept (renamed, described,
 * given a picture, deleted when unused) on the Professions screen of the kit admin (owner, 2026-10-24).
 */
@Getter
@Setter
@Entity
@Table(name = "profession")
public class Profession extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    @Column(nullable = false, length = 100)
    private String label;

    /** Developer-toggleable demo content (2026-09-29) — bookkeeping only, not read by any query
     *  filter. A profession with zero currently-visible kits already drops out of the public "Shop
     *  by profession" grouping on its own (see KitService.listPublicKits), so toggling mock kits'
     *  backing products is enough to hide mock professions too without this flag being consulted. */
    @Column(nullable = false)
    private boolean isMockData = false;

    /** A line or two about the trade, shown at the top of its kits in the shop (owner, 2026-10-24); null = none. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** The trade's own picture, shown beside its description; null = none. */
    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;
}
