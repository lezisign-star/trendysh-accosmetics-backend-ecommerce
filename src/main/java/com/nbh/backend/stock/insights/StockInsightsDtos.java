package com.nbh.backend.stock.insights;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for the Forecast tab, reorder levels, and the stock-alert settings. */
public final class StockInsightsDtos {

    private StockInsightsDtos() {}

    /**
     * One sellable variant's stock outlook. {@code status} is OUT_NOW (none left and it sells),
     * CRITICAL (runs out within 7 days), SOON (within 14), OK, or NO_DATA (no sales in the window, so
     * there is nothing to forecast from). {@code daysOfCover}/{@code runsOutOn} are null with no sales.
     */
    public record ForecastRow(
            UUID variantId, UUID productId, String productName, String variantName, String sku,
            BigDecimal available, BigDecimal soldLast30, BigDecimal soldLast7,
            BigDecimal avgPerDay, BigDecimal avgPerDayLast7, String trend,
            BigDecimal daysOfCover, LocalDate runsOutOn, BigDecimal onOrder,
            BigDecimal suggestedRestockQty, BigDecimal reorderLevel, BigDecimal suggestedReorderLevel,
            boolean belowReorderLevel, String status, boolean lowConfidence, boolean demo) {}

    /** {@code needsAttention} counts each variant once: out now, critical, or at/below its reorder level. */
    public record Summary(int outNow, int critical, int soon, int ok, int noData,
                          int belowReorderLevel, int needsAttention) {}

    public record ForecastResponse(Instant generatedAt, int windowDays, int leadTimeDays, int safetyDays,
                                    Summary summary, List<ForecastRow> rows) {}

    /** {@code reorderLevel} null clears it (no alert for this variant). */
    public record ReorderLevelRequest(BigDecimal reorderLevel) {}

    public record ReorderLevelResult(UUID variantId, BigDecimal reorderLevel) {}

    public record ApplySuggestedRequest(@NotEmpty List<UUID> variantIds) {}

    public record ApplySuggestedResult(int updated, int skipped) {}

    public record Recipient(UUID staffId, String fullName, String email, String roleDisplayName, boolean receives) {}

    public record AlertSettingsDto(int leadTimeDays, int safetyDays, boolean digestEnabled, List<Recipient> recipients) {}

    public record AlertSettingsRequest(@Min(0) @Max(120) int leadTimeDays,
                                       @Min(0) @Max(60) int safetyDays,
                                       boolean digestEnabled) {}

    public record RecipientRequest(boolean receives) {}
}
