package com.nbh.backend.order.entity;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.stock.entity.StockReservation;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Deliberately NOT extending BaseEntity — no updatedAt: an order line is
 * immutable once the order is placed (a change means cancelling and
 * re-ordering, not editing history), mirroring the append-only posture used
 * elsewhere in this codebase for anything that becomes a record of what
 * actually happened. The only things that move afterwards are how much has been
 * returned and the pick-and-pack tick.
 */
@Getter
@Setter
@Entity
@Table(name = "customer_order_item")
public class CustomerOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    /** Where this line sits in its order (V70): the order it was entered in, a package's products following the package. */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    /**
     * The pick-and-pack tick (V70): when staff packed this line and who did. Only a line that is physically picked has one
     * (a plain product, or a product inside a package), never a package's own summary line. Set and cleared by
     * {@code OrderFulfilmentService.setPacked}; nothing else about a placed order's lines ever changes except returns.
     */
    @Column(name = "packed_at")
    private java.time.Instant packedAt;

    @Column(name = "packed_by", length = 200)
    private String packedBy;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal vatRate;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    /** Snapshot of whether {@link #unitPrice} was the variant's wholesale price at checkout. */
    @Column(name = "wholesale_applied", nullable = false)
    private boolean wholesaleApplied = false;

    /** Filled in at fulfilment time from the stock ledger's outbound entry — the real margin figure. */
    // Phase 0 fix (2026-09-27), D7: was scale 2, silently truncating the 4dp moving-average
    // cost this is sourced from (see StockLedgerEntry.unitCost's comment).
    @Column(name = "unit_cost_at_sale", precision = 14, scale = 4)
    private BigDecimal unitCostAtSale;

    /** How much of {@link #qty} has already come back via {@code OrderReturnService} — caps further returns. */
    @Column(name = "returned_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal returnedQty = BigDecimal.ZERO;

    /** The hold this line is backed by — consumed (closed) when StockPostingService fulfils it at dispatch. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private StockReservation reservation;

    /**
     * Null for a normal line, and null for a package's own summary line (variant = the package's stockless
     * variant, priced at the package price, {@code reservation = null}). Set only on a package's real
     * constituent lines, each pointing back at that summary line — those are what carry the genuine
     * {@link #reservation} and post real stock movements; the order subtotal is only ever summed
     * from parentless lines, so a component's price is never double-counted. See
     * {@code CheckoutService}'s reservation/assembly loop and {@code PackageService}'s javadoc.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_item_id")
    private CustomerOrderItem parentItem;

    // What was bought, as it was when the order was placed (V62). Every one is nullable: an order placed before V62
    // carries none, and the sold*() readers below then answer from the live variant. Renaming a variant or a product,
    // or changing a variant's colour, size or SKU, therefore never rewrites an order that already exists.

    @Column(name = "product_name_snapshot", length = 250)
    private String productNameSnapshot;

    @Column(name = "variant_name_snapshot", length = 250)
    private String variantNameSnapshot;

    @Column(name = "sku_snapshot", length = 60)
    private String skuSnapshot;

    /** The variant's stored short option, null when it had none; see {@link #soldOptionLabel()}. */
    @Column(name = "option_label_snapshot", length = 160)
    private String optionLabelSnapshot;

    @Column(name = "option_color_snapshot", length = 80)
    private String optionColorSnapshot;

    @Column(name = "option_size_snapshot", length = 80)
    private String optionSizeSnapshot;

    @Column(name = "swatch_hex_snapshot", length = 9)
    private String swatchHexSnapshot;

    @Column(name = "image_url_snapshot", length = 500)
    private String imageUrlSnapshot;

    /**
     * Freezes the variant as it is right now onto this line. {@code coverImageUrl} is the variant's own cover photo
     * (null when it has none). Called once, when the line is created.
     */
    public void rememberVariant(ProductVariant source, String coverImageUrl) {
        productNameSnapshot = source.getProduct().getName();
        variantNameSnapshot = source.getVariantName();
        skuSnapshot = source.getSku();
        optionLabelSnapshot = source.getOptionLabel();
        optionColorSnapshot = source.getOptionColor();
        optionSizeSnapshot = source.getOptionSize();
        swatchHexSnapshot = source.getSwatchHex();
        imageUrlSnapshot = coverImageUrl;
    }

    /**
     * True when this line froze its variant. The SKU stands for all of them: a live variant always has one, so a
     * frozen line always carries it, and the other fields are legitimately null for a product with one plain option.
     */
    public boolean hasSnapshot() {
        return skuSnapshot != null;
    }

    public String soldSku() {
        return hasSnapshot() ? skuSnapshot : variant.getSku();
    }

    public String soldProductName() {
        return productNameSnapshot != null ? productNameSnapshot : variant.getProduct().getName();
    }

    public String soldVariantName() {
        return hasSnapshot() ? variantNameSnapshot : variant.getVariantName();
    }

    /** The option on its own, "Ruby Red / 50ml": what {@code ProductVariant.shortOption()} said when it was ordered. */
    public String soldOptionLabel() {
        return hasSnapshot()
                ? ProductVariant.shortOptionOf(optionLabelSnapshot, variantNameSnapshot, soldProductName())
                : variant.shortOption();
    }

    public String soldOptionColor() {
        return hasSnapshot() ? optionColorSnapshot : variant.getOptionColor();
    }

    public String soldOptionSize() {
        return hasSnapshot() ? optionSizeSnapshot : variant.getOptionSize();
    }

    public String soldSwatchHex() {
        return hasSnapshot() ? swatchHexSnapshot : variant.getSwatchHex();
    }

    /** The photo it was ordered with; null for a line placed before photos were frozen (nothing is looked up live). */
    public String soldImageUrl() {
        return imageUrlSnapshot;
    }

    /** How a message names this line: "Gel Polish (Ruby Red)", or just "Gel Polish" when it has no option worth naming. */
    public String soldLabel() {
        String option = soldOptionLabel();
        String product = soldProductName();
        return option == null ? product : product + " (" + option + ")";
    }
}
