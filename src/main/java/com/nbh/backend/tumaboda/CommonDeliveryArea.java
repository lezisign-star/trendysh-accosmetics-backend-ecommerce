package com.nbh.backend.tumaboda;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A hand-curated, seeded fallback for TumaBoda address search — see {@link CommonDeliveryAreaSeeder}'s
 * javadoc for the list and {@link CommonDeliveryAreaService} for how it is used. Exists so the checkout
 * address picker always has something sensible to suggest even when TumaBoda's own live autocomplete
 * returns nothing (unreachable, rate-limited, too vague a query) or the integration is off entirely.
 * Purely a stopgap: once TumaBoda has its own reliable coverage-area data, this table is redundant.
 * Seed-only for now (no admin UI to edit it) — add one later only if it turns out to be worth staff time.
 */
@Entity
@Table(name = "common_delivery_area")
@Getter
@Setter
@NoArgsConstructor
public class CommonDeliveryArea extends BaseEntity {

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(length = 60)
    private String county;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(nullable = false)
    private boolean active = true;
}
