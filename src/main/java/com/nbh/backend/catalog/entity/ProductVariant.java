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

    /** Not deleted, not hidden, and has a price — the one definition of "can appear/be sold" everywhere. */
    public boolean isSellable() {
        return !deleted && isActive && sellingPrice != null;
    }
}
