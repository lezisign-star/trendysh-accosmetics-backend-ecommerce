package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.PromoCampaign;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;

/**
 * The one place a variant's real selling price is computed. Used on every read path that shows or
 * charges a price — the public catalogue (browse/detail, for display) and {@code CartService}
 * (for the price actually locked into a cart line, {@code CartItem.unitPriceSnapshot}). Checkout
 * itself never re-resolves a price: it trusts the cart's snapshot, the same way it already trusts
 * nothing else about the client — the snapshot is what makes a price server-computed and
 * tamper-proof without needing checkout to duplicate this logic.
 *
 * Rule: a variant's own flash deal always wins over an active site-wide campaign (most-specific-
 * wins), so a product can never be discounted twice by accident.
 *
 * A variant may also carry a wholesale tier ({@code wholesaleMinQty}/{@code wholesalePrice}) — once
 * {@code qty} reaches that threshold, its per-unit price is compared against whatever the deal/
 * campaign logic above already produced, and the cheaper of the two wins ("cheapest wins", not
 * "most-specific wins": a wholesale buyer should never pay more than a flash-deal shopper would).
 * Callers with no meaningful cart quantity (e.g. building a storefront card) pass {@link
 * BigDecimal#ZERO}, which can never qualify for a tier.
 */
@Service
public class EffectivePriceResolver {

    public enum Source { NONE, PRODUCT_DEAL, CAMPAIGN, WHOLESALE }

    public record EffectivePrice(BigDecimal originalPrice, BigDecimal effectivePrice, Instant dealEndsAt, Source source) {
        public boolean onDeal() {
            return source != Source.NONE;
        }
    }

    private final Clock clock;

    public EffectivePriceResolver(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param activeCampaign the currently-active campaign, or null if none is active right now
     * @param qty the quantity being priced (e.g. a cart line's total), or null/zero if there is no
     *        meaningful quantity context (e.g. a storefront card) — never qualifies for wholesale
     * @throws IllegalStateException if the variant has no price — callers on every sellable path
     *         must filter to {@link ProductVariant#isSellable()} first (Phase 3, 2026-09-24: a
     *         draft variant can now be saved with no price at all).
     */
    public EffectivePrice resolve(ProductVariant variant, PromoCampaign activeCampaign, BigDecimal qty) {
        BigDecimal original = variant.getSellingPrice();
        if (original == null) {
            throw new IllegalStateException("Variant " + variant.getId() + " has no price — filter to isSellable() first");
        }
        // A package's price is already a fixed, admin-chosen discount off its components' combined price
        // (see PackageService) — stacking a site-wide campaign or a per-variant deal on top of it would
        // silently discount it further than intended, and the public "Shop by profession" modal
        // always shows this same plain sellingPrice, so applying either here would make the price a
        // customer sees there disagree with what the shop grid and cart would charge.
        if (variant.isPackage()) {
            return new EffectivePrice(original, original, null, Source.NONE);
        }
        Instant now = Instant.now(clock);

        EffectivePrice nonWholesale;
        if (variant.getDealPrice() != null && variant.getDealEndsAt() != null && variant.getDealEndsAt().isAfter(now)) {
            nonWholesale = new EffectivePrice(original, variant.getDealPrice(), variant.getDealEndsAt(), Source.PRODUCT_DEAL);
        } else if (activeCampaign != null) {
            BigDecimal factor = BigDecimal.ONE.subtract(
                    activeCampaign.getPercentOff().movePointLeft(2));
            BigDecimal discounted = original.multiply(factor).setScale(2, RoundingMode.HALF_UP);
            nonWholesale = new EffectivePrice(original, discounted, activeCampaign.getEndsAt(), Source.CAMPAIGN);
        } else {
            nonWholesale = new EffectivePrice(original, original, null, Source.NONE);
        }

        if (variant.getWholesaleMinQty() != null && variant.getWholesalePrice() != null && qty != null
                && qty.compareTo(BigDecimal.valueOf(variant.getWholesaleMinQty())) >= 0
                && variant.getWholesalePrice().compareTo(nonWholesale.effectivePrice()) < 0) {
            return new EffectivePrice(original, variant.getWholesalePrice(), null, Source.WHOLESALE);
        }
        return nonWholesale;
    }
}
