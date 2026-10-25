package com.nbh.backend.packages.entity;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An admin-curated bundle of existing product variants + fixed quantities, sold at one fixed
 * package price. The package itself IS a real {@link com.nbh.backend.catalog.entity.Product} +
 * {@link ProductVariant} (see {@link ProductVariant#isPackage}) — this entity holds only the
 * admin-curated bundle definition (which real components make it up), keyed by that variant.
 * The package price lives on {@code variant.sellingPrice}, same as any other product — a fixed
 * snapshot, not recomputed from the components' prices after the fact.
 */
@Getter
@Setter
@Entity
@Table(name = "product_package")
public class ProductPackage extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false, unique = true)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profession_id", nullable = false)
    private Profession profession;

    /** Required — the "who this package is for" line (e.g. "for beginners/first-timers") that lets a
     *  customer tell several packages under the same profession apart. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    /**
     * Other trades the package is also listed under (owner, 2026-10-24); its main one is {@link #profession}, which gives
     * it its link, breadcrumb and admin filter.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "package_extra_profession", joinColumns = @JoinColumn(name = "package_id"),
            inverseJoinColumns = @JoinColumn(name = "profession_id"))
    private Set<Profession> extraProfessions = new LinkedHashSet<>();

    @OneToMany(mappedBy = "productPackage", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<PackageComponent> components = new ArrayList<>();
}
