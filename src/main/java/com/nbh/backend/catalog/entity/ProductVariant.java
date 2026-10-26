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

    /** Short option shown by the variant picker ("Emerald Green / Large"); null = show {@link #variantName}. */
    @Column(name = "option_label", length = 160)
    private String optionLabel;

    @Column(name = "option_color", length = 80)
    private String optionColor;

    @Column(name = "option_size", length = 80)
    private String optionSize;

    /** The picker's colour dot, "#RRGGBB"; null = none known. */
    @Column(name = "swatch_hex", length = 9)
    private String swatchHex;

    /** Staff's choice of {@link ColorFamily} by name (V63); null = worked out from {@link #swatchHex}. See {@link #effectiveColorFamily()}. */
    @Column(name = "color_family", length = 20)
    private String colorFamily;

    /** Where {@link #optionSize} sits in the product's size order, 0 first (V63); null = no size. Kept by {@code ProductOptionsService.sync}. */
    @Column(name = "size_position")
    private Integer sizePosition;

    /** Set on a variant the AI generated: the variant it was made from, and the run that made it. */
    @Column(name = "generated_from_variant_id")
    private java.util.UUID generatedFromVariantId;

    @Column(name = "generation_batch_id")
    private java.util.UUID generationBatchId;

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

    /**
     * Who last saved this variant (V73): the staff member, or "system" for a background job. A trigger copies it into the
     * price history when a save changes the selling price.
     */
    @org.springframework.data.annotation.LastModifiedBy
    @Column(name = "updated_by", length = 200)
    private String updatedBy;

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
     * True for the one variant that represents an admin-curated {@code ProductPackage} — see that package's
     * javadoc. A package variant holds no {@code StockBalance} of its own (the real stock lives on its
     * components), so {@code CatalogService.toCard} and {@code CartService}'s courtesy availability
     * check ask {@code PackageAvailabilityService} instead — how many whole packages the components can make
     * up — and checkout enforces the real availability by reserving each component.
     */
    @Column(name = "is_package", nullable = false)
    private boolean isPackage = false;

    /** The variant's own part of its name (V68): its full name is the range's common name, a dash, and this. */
    @Column(name = "name_part", length = 200)
    private String namePart;

    /** A category of its own, used only when its range lets categories differ (V68); null = the range's. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** Where its stock number comes from (V78): none yet, estimated in bulk, or counted. See {@link StockBasis}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "stock_basis", nullable = false, length = 12)
    private StockBasis stockBasis = StockBasis.NONE;

    /** The colour family the shop filters this variant under: staff's choice, else what its dot looks like; null = no colour. */
    public ColorFamily effectiveColorFamily() {
        ColorFamily chosen = ColorFamily.ofStored(colorFamily);
        return chosen != null ? chosen : ColorFamily.fromHex(swatchHex);
    }

    /** Not deleted, not hidden, and has a price — the one definition of "can appear/be sold" everywhere. */
    public boolean isSellable() {
        return !deleted && isActive && sellingPrice != null;
    }

    /**
     * How a message to a shopper or to staff names this item: the product's name, with the option's name in brackets
     * after it — unless that name says nothing (blank, which is what the product editor saves for a product with one
     * option, or the placeholder "Default"), in which case it is just the product's name. Messages used to compare
     * with "Default" alone, so a product made in the editor was announced as "Gel polish ()".
     */
    public String displayLabel() {
        String option = shortOption();
        return option == null ? product.getName() : product.getName() + " (" + option + ")";
    }

    /**
     * The option on its own, "Emerald Green" or "Large": the stored {@code optionLabel} when there is one (generated
     * variants), else the variant's name with a leading product title and dash removed ("Pot Set – Emerald Green"), else
     * the name as is. Null when the name says nothing (blank or "Default").
     */
    public String shortOption() {
        return shortOptionOf(optionLabel, variantName, product.getName());
    }

    /**
     * {@link #shortOption()} from its three inputs. An order line freezes those three when it is placed, so it works
     * out the same text from what it froze instead of from the variant as it is now.
     */
    public static String shortOptionOf(String optionLabel, String variantName, String productName) {
        if (optionLabel != null && !optionLabel.isBlank()) {
            return optionLabel.trim();
        }
        if (com.nbh.backend.catalog.dto.CatalogVariantCardDto.isMeaninglessName(variantName)) {
            return null;
        }
        String name = variantName.trim();
        String title = productName == null ? "" : productName.trim();
        if (!title.isEmpty() && name.length() > title.length()
                && name.toLowerCase(java.util.Locale.ROOT).startsWith(title.toLowerCase(java.util.Locale.ROOT))) {
            String rest = name.substring(title.length()).replaceFirst("^[\\s\\u2013\\u2014:|-]+", "").trim();
            return rest.isEmpty() ? name : rest;
        }
        return name;
    }
}
