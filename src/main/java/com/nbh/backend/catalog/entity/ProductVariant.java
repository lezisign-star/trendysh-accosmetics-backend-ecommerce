package com.nbh.backend.catalog.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The SKU — the only stockable, sellable thing. A "foundation with 24 shades" is
 * one {@link Product} and 24 of these, each with its own barcode, cost and price.
 *
 * {@code standardCost} is a human-entered reference figure only. The real cost
 * used for COGS/valuation is the moving-average {@code avgCost} on
 * {@code stock_balance}, maintained purely from actual stock-ledger receipts —
 * never conflate the two.
 */
@Getter
@Setter
@jakarta.persistence.EntityListeners(com.nbh.backend.live.EntityChangeListener.class)
@Entity
@Table(name = "product_variant")
public class ProductVariant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, unique = true, length = 60)
    private String sku;

    @Column(nullable = false, length = 250)
    private String variantName;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Position within its product on the editor page and the storefront's sibling strip. */
    @Column(nullable = false)
    private int sortOrder = 0;

    /**
     * Nullable since Phase 3 (2026-09-24): a draft variant can be saved with no price at all —
     * publishing only WARNS about this (never blocks a save), so a priced-less variant needs a
     * real "not sellable yet" state rather than a fake zero. See {@link #isSellable()} and every
     * caller that reads this field for a null check; {@code EffectivePriceResolver} and
     * {@code VariantDealService} both guard it explicitly.
     */
    @Column(precision = 14, scale = 2)
    private BigDecimal sellingPrice;

    @Column(precision = 14, scale = 2)
    private BigDecimal compareAtPrice;

    /**
     * A per-variant flash deal: sale price + its own deadline, independent of any site-wide
     * {@code PromoCampaign}. Both null = no deal; {@code VariantDealService} enforces they're set
     * or cleared together. Wins over an active campaign when both would apply — see
     * {@code EffectivePriceResolver}.
     */
    @Column(name = "deal_price", precision = 14, scale = 2)
    private BigDecimal dealPrice;

    @Column(name = "deal_ends_at")
    private Instant dealEndsAt;

    /**
     * A single wholesale tier: reach this quantity of the SKU in one cart line and the price for the
     * WHOLE line becomes wholesalePrice (not just the units above the threshold). Both null = no tier;
     * WholesalePricingService enforces they're set/cleared together, same pattern as
     * dealPrice/dealEndsAt. Cheapest-wins against a deal/campaign — see EffectivePriceResolver.
     */
    @Column(name = "wholesale_min_qty")
    private Integer wholesaleMinQty;

    @Column(name = "wholesale_price", precision = 14, scale = 2)
    private BigDecimal wholesalePrice;

    @Column(precision = 14, scale = 2)
    private BigDecimal standardCost;

    @Column(nullable = false, length = 20)
    private String uom = "PIECE";

    @Column(precision = 10, scale = 2)
    private BigDecimal weightGrams;

    @Column(precision = 10, scale = 2)
    private BigDecimal volumeMl;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal vatRate = new BigDecimal("0.1600");

    @Column(nullable = false)
    private boolean vatExempt = false;

    @Column(precision = 14, scale = 3)
    private BigDecimal reorderLevel;

    @Column(nullable = false)
    private boolean isActive = true;

    @Column(nullable = false)
    private boolean deleted = false;

    /**
     * True for the one variant that represents an admin-curated {@code Kit} — see that package's
     * javadoc. A kit variant holds no {@code StockBalance} of its own (the real stock lives on its
     * components), so both {@code CatalogService.toCard} and {@code CartService}'s courtesy
     * availability check bypass their normal stock lookup for it; checkout enforces the real
     * availability by reserving each component instead.
     */
    @Column(name = "is_kit", nullable = false)
    private boolean isKit = false;

    /** Not deleted, not hidden, and has a price — the one definition of "can appear/be sold" everywhere. */
    public boolean isSellable() {
        return !deleted && isActive && sellingPrice != null;
    }
}
