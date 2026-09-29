package com.nbh.backend.stock.insights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The arithmetic behind the Forecast tab, kept free of repositories and clocks so it can be tested
 * with plain numbers. Demand is "units sold over the last {@link #WINDOW_DAYS} days, per day".
 *
 * <p>Known limitation: days when the variant was out of stock have no sales, so they pull the average
 * down — a product that keeps selling out is under-forecast. The OUT_NOW status and the 7-day trend
 * are what surface that case.
 */
final class ForecastMath {

    static final int WINDOW_DAYS = 30;
    static final int TREND_DAYS = 7;

    enum Status { OUT_NOW, CRITICAL, SOON, OK, NO_DATA }

    record Result(BigDecimal avgPerDay, BigDecimal avgPerDayLast7, String trend, BigDecimal daysOfCover,
                  LocalDate runsOutOn, BigDecimal suggestedRestockQty, BigDecimal suggestedReorderLevel,
                  boolean belowReorderLevel, Status status) {}

    private ForecastMath() {}

    /**
     * @param available    sellable stock now (on-hand minus reserved); negative counts as none
     * @param soldWindow   units sold over the last {@link #WINDOW_DAYS} days
     * @param soldLast7    units sold over the last {@link #TREND_DAYS} days
     * @param onOrder      units on open supplier orders not yet received
     * @param reorderLevel the variant's own alert level, or null
     */
    static Result compute(BigDecimal available, BigDecimal soldWindow, BigDecimal soldLast7, BigDecimal onOrder,
                          BigDecimal reorderLevel, int leadTimeDays, int safetyDays, LocalDate today) {
        BigDecimal avail = orZero(available);
        BigDecimal usable = avail.max(BigDecimal.ZERO);
        BigDecimal window = orZero(soldWindow);
        BigDecimal last7 = orZero(soldLast7);
        BigDecimal ordered = orZero(onOrder);
        boolean hasSales = window.signum() > 0;

        BigDecimal avg = window.divide(BigDecimal.valueOf(WINDOW_DAYS), 2, RoundingMode.HALF_UP);
        BigDecimal avg7 = last7.divide(BigDecimal.valueOf(TREND_DAYS), 2, RoundingMode.HALF_UP);

        BigDecimal daysOfCover = null;
        LocalDate runsOutOn = null;
        BigDecimal restock = BigDecimal.ZERO;
        BigDecimal suggestedReorder = null;
        Status status = Status.NO_DATA;

        if (hasSales) {
            // usable / (window / 30) — written this way so the average is never rounded before it is used.
            daysOfCover = usable.multiply(BigDecimal.valueOf(WINDOW_DAYS)).divide(window, 1, RoundingMode.HALF_UP);
            runsOutOn = today.plusDays(daysOfCover.setScale(0, RoundingMode.DOWN).longValue());
            restock = window.subtract(usable).subtract(ordered).setScale(0, RoundingMode.CEILING).max(BigDecimal.ZERO);
            suggestedReorder = window.multiply(BigDecimal.valueOf((long) leadTimeDays + safetyDays))
                    .divide(BigDecimal.valueOf(WINDOW_DAYS), 0, RoundingMode.CEILING).max(BigDecimal.ONE);

            if (avail.signum() <= 0) status = Status.OUT_NOW;
            else if (daysOfCover.compareTo(BigDecimal.valueOf(7)) <= 0) status = Status.CRITICAL;
            else if (daysOfCover.compareTo(BigDecimal.valueOf(14)) <= 0) status = Status.SOON;
            else status = Status.OK;
        }

        String trend = "FLAT";
        if (last7.compareTo(BigDecimal.valueOf(3)) >= 0 && avg7.compareTo(avg.multiply(new BigDecimal("1.5"))) >= 0) {
            trend = "UP";
        } else if (hasSales && avg7.compareTo(avg.multiply(new BigDecimal("0.5"))) <= 0) {
            trend = "DOWN";
        }

        boolean below = reorderLevel != null && avail.compareTo(reorderLevel) <= 0;
        return new Result(avg, avg7, trend, daysOfCover, runsOutOn, restock, suggestedReorder, below, status);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
