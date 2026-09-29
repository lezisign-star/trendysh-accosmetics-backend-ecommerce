package com.nbh.backend.stock.insights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The forecast arithmetic with plain numbers: no database, no clock. */
class ForecastMathUnitTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static ForecastMath.Result compute(String available, String sold30, String sold7, String onOrder, String reorder) {
        return ForecastMath.compute(n(available), n(sold30), n(sold7), n(onOrder),
                reorder == null ? null : n(reorder), 7, 3, TODAY);
    }

    @Test
    void averagesDaysLeftAndSuggestionsFollowFromLastThirtyDaysOfSales() {
        // 60 sold in 30 days = 2 a day; 12 on the shelf = 6 days of cover.
        var r = compute("12", "60", "14", "0", null);

        assertThat(r.avgPerDay()).isEqualByComparingTo("2.00");
        assertThat(r.daysOfCover()).isEqualByComparingTo("6.0");
        assertThat(r.runsOutOn()).isEqualTo(TODAY.plusDays(6));
        assertThat(r.status()).isEqualTo(ForecastMath.Status.CRITICAL);
        assertThat(r.suggestedRestockQty()).isEqualByComparingTo("48");      // a month's sales (60) less what is here (12)
        assertThat(r.suggestedReorderLevel()).isEqualByComparingTo("20");    // 2/day over 7 days' lead time + 3 safety
        assertThat(r.trend()).isEqualTo("FLAT");
    }

    @Test
    void statusFollowsDaysOfCover() {
        assertThat(compute("0", "30", "7", "0", null).status()).isEqualTo(ForecastMath.Status.OUT_NOW);
        assertThat(compute("7", "30", "7", "0", null).status()).isEqualTo(ForecastMath.Status.CRITICAL);   // exactly 7 days
        assertThat(compute("10", "24", "5", "0", null).status()).isEqualTo(ForecastMath.Status.SOON);      // 12.5 days
        assertThat(compute("100", "30", "7", "0", null).status()).isEqualTo(ForecastMath.Status.OK);
    }

    @Test
    void noSalesMeansNothingToForecastFrom() {
        var r = compute("50", "0", "0", "0", null);

        assertThat(r.status()).isEqualTo(ForecastMath.Status.NO_DATA);
        assertThat(r.daysOfCover()).isNull();
        assertThat(r.runsOutOn()).isNull();
        assertThat(r.suggestedRestockQty()).isEqualByComparingTo("0");
        assertThat(r.suggestedReorderLevel()).isNull();
    }

    @Test
    void stockAlreadyOnOrderReducesTheSuggestedRestockButNeverBelowZero() {
        assertThat(compute("12", "60", "14", "20", null).suggestedRestockQty()).isEqualByComparingTo("28");
        assertThat(compute("12", "60", "14", "500", null).suggestedRestockQty()).isEqualByComparingTo("0");
    }

    @Test
    void oversoldStockCountsAsNone() {
        var r = compute("-3", "60", "14", "0", null);

        assertThat(r.status()).isEqualTo(ForecastMath.Status.OUT_NOW);
        assertThat(r.suggestedRestockQty()).isEqualByComparingTo("60");
    }

    @Test
    void reorderLevelSuggestionRoundsUpAndIsAtLeastOne() {
        assertThat(compute("50", "7", "1", "0", null).suggestedReorderLevel()).isEqualByComparingTo("3");  // 7 x 10 / 30 = 2.33
        assertThat(compute("50", "1", "0", "0", null).suggestedReorderLevel()).isEqualByComparingTo("1");  // 0.33 -> at least 1
    }

    @Test
    void belowReorderLevelMeansAtOrUnderIt() {
        assertThat(compute("5", "30", "7", "0", "5").belowReorderLevel()).isTrue();
        assertThat(compute("6", "30", "7", "0", "5").belowReorderLevel()).isFalse();
        assertThat(compute("0", "0", "0", "0", "5").belowReorderLevel()).isTrue();   // nothing left is below any level
        assertThat(compute("1", "30", "7", "0", null).belowReorderLevel()).isFalse();
    }

    @Test
    void trendShowsARecentSurgeOrSlump() {
        assertThat(compute("50", "30", "14", "0", null).trend()).isEqualTo("UP");    // 2/day this week vs 1/day
        assertThat(compute("50", "60", "2", "0", null).trend()).isEqualTo("DOWN");   // barely selling this week
        assertThat(compute("50", "30", "7", "0", null).trend()).isEqualTo("FLAT");
        assertThat(compute("50", "1", "2", "0", null).trend()).isEqualTo("FLAT");    // too few sales to call a surge
    }
}
