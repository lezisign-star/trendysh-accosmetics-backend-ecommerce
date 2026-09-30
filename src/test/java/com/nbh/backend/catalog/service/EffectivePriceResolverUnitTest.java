package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.PromoCampaign;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place a variant's real price is computed — every rule here matters, since it's also
 * what gets charged (via CartService's snapshot), not just displayed.
 */
class EffectivePriceResolverUnitTest {

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EffectivePriceResolver resolver = new EffectivePriceResolver(clock);

    private static ProductVariant variant(String price) {
        ProductVariant v = new ProductVariant();
        v.setSellingPrice(new BigDecimal(price));
        return v;
    }

    private static PromoCampaign campaign(String percentOff, Instant endsAt) {
        PromoCampaign c = new PromoCampaign();
        c.setPercentOff(new BigDecimal(percentOff));
        c.setEndsAt(endsAt);
        return c;
    }

    @Test
    void noDealAndNoCampaignReturnsThePlainSellingPrice() {
        var result = resolver.resolve(variant("1000"), null, BigDecimal.ZERO);

        assertThat(result.effectivePrice()).isEqualByComparingTo("1000");
        assertThat(result.originalPrice()).isEqualByComparingTo("1000");
        assertThat(result.dealEndsAt()).isNull();
        assertThat(result.onDeal()).isFalse();
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.NONE);
    }

    @Test
    void anActiveCampaignDiscountsTheSellingPrice() {
        var v = variant("1000");
        var result = resolver.resolve(v, campaign("20", NOW.plusSeconds(3600)), BigDecimal.ZERO);

        assertThat(result.effectivePrice()).isEqualByComparingTo("800.00");
        assertThat(result.originalPrice()).isEqualByComparingTo("1000");
        assertThat(result.dealEndsAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.CAMPAIGN);
    }

    @Test
    void aProductsOwnDealWinsOverAnActiveCampaign() {
        var v = variant("1000");
        v.setDealPrice(new BigDecimal("650"));
        v.setDealEndsAt(NOW.plusSeconds(1800));

        var result = resolver.resolve(v, campaign("20", NOW.plusSeconds(3600)), BigDecimal.ZERO);

        assertThat(result.effectivePrice()).isEqualByComparingTo("650");
        assertThat(result.dealEndsAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.PRODUCT_DEAL);
    }

    @Test
    void anExpiredProductDealIsIgnoredEvenWithNoCampaign() {
        var v = variant("1000");
        v.setDealPrice(new BigDecimal("650"));
        v.setDealEndsAt(NOW.minusSeconds(1));

        var result = resolver.resolve(v, null, BigDecimal.ZERO);

        assertThat(result.effectivePrice()).isEqualByComparingTo("1000");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.NONE);
    }

    @Test
    void anExpiredProductDealFallsThroughToAnActiveCampaign() {
        var v = variant("1000");
        v.setDealPrice(new BigDecimal("650"));
        v.setDealEndsAt(NOW.minusSeconds(1));

        var result = resolver.resolve(v, campaign("10", NOW.plusSeconds(60)), BigDecimal.ZERO);

        assertThat(result.effectivePrice()).isEqualByComparingTo("900.00");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.CAMPAIGN);
    }

    @Test
    void aDealThatEndsExactlyNowIsTreatedAsExpired() {
        var v = variant("1000");
        v.setDealPrice(new BigDecimal("650"));
        v.setDealEndsAt(NOW); // isAfter(now) is false when equal

        var result = resolver.resolve(v, null, BigDecimal.ZERO);

        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.NONE);
    }

    @Test
    void roundsTheCampaignPriceToTwoDecimalPlaces() {
        var v = variant("999.99");
        var result = resolver.resolve(v, campaign("33.33", NOW.plusSeconds(60)), BigDecimal.ZERO);

        // 999.99 * (1 - 0.3333) = 999.99 * 0.6667 = 666.693333... -> 666.69
        assertThat(result.effectivePrice()).isEqualByComparingTo("666.69");
    }

    // --- Wholesale tier ---

    private static ProductVariant variantWithWholesale(String price, int minQty, String wholesalePrice) {
        ProductVariant v = variant(price);
        v.setWholesaleMinQty(minQty);
        v.setWholesalePrice(new BigDecimal(wholesalePrice));
        return v;
    }

    @Test
    void qtyBelowThresholdIgnoresWholesaleTier() {
        var v = variantWithWholesale("1000", 10, "700");
        var result = resolver.resolve(v, null, new BigDecimal("9"));

        assertThat(result.effectivePrice()).isEqualByComparingTo("1000");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.NONE);
    }

    @Test
    void qtyAtThresholdAppliesWholesalePrice() {
        var v = variantWithWholesale("1000", 10, "700");
        var result = resolver.resolve(v, null, new BigDecimal("10"));

        assertThat(result.effectivePrice()).isEqualByComparingTo("700");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.WHOLESALE);
        assertThat(result.dealEndsAt()).isNull();
    }

    @Test
    void qtyAboveThresholdAppliesWholesalePrice() {
        var v = variantWithWholesale("1000", 10, "700");
        var result = resolver.resolve(v, null, new BigDecimal("25"));

        assertThat(result.effectivePrice()).isEqualByComparingTo("700");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.WHOLESALE);
    }

    @Test
    void wholesaleCheaperThanActiveCampaignWins() {
        var v = variantWithWholesale("1000", 10, "700");
        // Campaign discounts to 800, wholesale (700) is cheaper.
        var result = resolver.resolve(v, campaign("20", NOW.plusSeconds(60)), new BigDecimal("10"));

        assertThat(result.effectivePrice()).isEqualByComparingTo("700");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.WHOLESALE);
    }

    @Test
    void campaignCheaperThanWholesaleWins() {
        var v = variantWithWholesale("1000", 10, "700");
        // Campaign discounts to 500, cheaper than wholesale's 700.
        var result = resolver.resolve(v, campaign("50", NOW.plusSeconds(60)), new BigDecimal("10"));

        assertThat(result.effectivePrice()).isEqualByComparingTo("500.00");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.CAMPAIGN);
    }

    @Test
    void wholesaleCheaperThanProductDealWins() {
        var v = variantWithWholesale("1000", 10, "600");
        v.setDealPrice(new BigDecimal("650"));
        v.setDealEndsAt(NOW.plusSeconds(60));

        var result = resolver.resolve(v, null, new BigDecimal("10"));

        assertThat(result.effectivePrice()).isEqualByComparingTo("600");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.WHOLESALE);
    }

    @Test
    void productDealCheaperThanWholesaleWins() {
        var v = variantWithWholesale("1000", 10, "650");
        v.setDealPrice(new BigDecimal("600"));
        v.setDealEndsAt(NOW.plusSeconds(60));

        var result = resolver.resolve(v, null, new BigDecimal("10"));

        assertThat(result.effectivePrice()).isEqualByComparingTo("600");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.PRODUCT_DEAL);
    }

    @Test
    void tieBetweenWholesaleAndDealPicksTheNonWholesaleSource() {
        var v = variantWithWholesale("1000", 10, "650");
        v.setDealPrice(new BigDecimal("650"));
        v.setDealEndsAt(NOW.plusSeconds(60));

        var result = resolver.resolve(v, null, new BigDecimal("10"));

        assertThat(result.effectivePrice()).isEqualByComparingTo("650");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.PRODUCT_DEAL);
    }

    @Test
    void noWholesaleTierConfiguredIsUnaffectedByQty() {
        var v = variant("1000");
        var result = resolver.resolve(v, null, new BigDecimal("1000"));

        assertThat(result.effectivePrice()).isEqualByComparingTo("1000");
        assertThat(result.source()).isEqualTo(EffectivePriceResolver.Source.NONE);
    }
}
