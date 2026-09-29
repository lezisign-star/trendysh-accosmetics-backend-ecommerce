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
 * its own {@code description} to tell them apart. Managed inline from the kit admin form (no
 * dedicated screen), same shape as {@code Supplier}/{@code Category}.
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
}
