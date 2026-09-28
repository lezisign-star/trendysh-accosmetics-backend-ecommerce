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
 */
@Service
public class EffectivePriceResolver {

    public enum Source { NONE, PRODUCT_DEAL, CAMPAIGN }

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
     * @throws IllegalStateException if the variant has no price — callers on every sellable path
     *         must filter to {@link ProductVariant#isSellable()} first (Phase 3, 2026-09-24: a
     *         draft variant can now be saved with no price at all).
     */
    public EffectivePrice resolve(ProductVariant variant, PromoCampaign activeCampaign) {
        BigDecimal original = variant.getSellingPrice();
        if (original == null) {
            throw new IllegalStateException("Variant " + variant.getId() + " has no price — filter to isSellable() first");
        }
        // A kit's price is already a fixed, admin-chosen discount off its components' combined price
        // (see KitService) — stacking a site-wide campaign or a per-variant deal on top of it would
        // silently discount it further than intended, and the public "Shop by profession" modal
        // always shows this same plain sellingPrice, so applying either here would make the price a
        // customer sees there disagree with what the shop grid and cart would charge.
        if (variant.isKit()) {
            return new EffectivePrice(original, original, null, Source.NONE);
        }
        Instant now = Instant.now(clock);

        if (variant.getDealPrice() != null && variant.getDealEndsAt() != null && variant.getDealEndsAt().isAfter(now)) {
            return new EffectivePrice(original, variant.getDealPrice(), variant.getDealEndsAt(), Source.PRODUCT_DEAL);
        }
        if (activeCampaign != null) {
            BigDecimal factor = BigDecimal.ONE.subtract(
                    activeCampaign.getPercentOff().movePointLeft(2));
            BigDecimal discounted = original.multiply(factor).setScale(2, RoundingMode.HALF_UP);
            return new EffectivePrice(original, discounted, activeCampaign.getEndsAt(), Source.CAMPAIGN);
        }
        return new EffectivePrice(original, original, null, Source.NONE);
    }
}
