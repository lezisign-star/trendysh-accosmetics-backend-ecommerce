package com.nbh.backend.kits.entity;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * An admin-curated bundle of existing product variants + fixed quantities, sold at one fixed
 * package price. The kit itself IS a real {@link com.nbh.backend.catalog.entity.Product} +
 * {@link ProductVariant} (see {@link ProductVariant#isKit}) — this entity holds only the
 * admin-curated bundle definition (which real components make it up), keyed by that variant.
 * The package price lives on {@code variant.sellingPrice}, same as any other product — a fixed
 * snapshot, not recomputed from the components' prices after the fact.
 */
@Getter
@Setter
@Entity
@Table(name = "kit")
public class Kit extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false, unique = true)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profession_id", nullable = false)
    private Profession profession;

    /** Required — the "who this kit is for" line (e.g. "for beginners/first-timers") that lets a
     *  customer tell several kits under the same profession apart. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @OneToMany(mappedBy = "kit", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<KitComponent> components = new ArrayList<>();
}
