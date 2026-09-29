package com.nbh.backend.stock.insights;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductSaleEventRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.purchasing.entity.PurchaseOrderStatus;
import com.nbh.backend.purchasing.repository.PurchaseOrderRepository;
import com.nbh.backend.stock.insights.StockInsightsDtos.ForecastResponse;
import com.nbh.backend.stock.insights.StockInsightsDtos.ForecastRow;
import com.nbh.backend.stock.insights.StockInsightsDtos.Summary;
import com.nbh.backend.stock.service.StockAvailabilityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns sales history plus current stock into "what runs out, when, and how much to order" for every
 * variant on sale. Demand comes from {@code product_sale_event} (recorded when a payment is confirmed),
 * availability from {@link StockAvailabilityService} (the one definition of "available"), and stock
 * already on its way from open purchase orders. The arithmetic itself is in {@link ForecastMath}.
 */
@Service
public class StockForecastService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    private static final List<PurchaseOrderStatus> OPEN_ORDER_STATUSES =
            List.of(PurchaseOrderStatus.SENT, PurchaseOrderStatus.PARTIALLY_RECEIVED);

    private final ProductVariantRepository variantRepository;
    private final ProductSaleEventRepository saleEventRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final StockAvailabilityService availabilityService;
    private final StockAlertSettingsService settingsService;
    private final Clock clock;

    public StockForecastService(ProductVariantRepository variantRepository,
                                 ProductSaleEventRepository saleEventRepository,
                                 PurchaseOrderRepository purchaseOrderRepository,
                                 StockAvailabilityService availabilityService,
                                 StockAlertSettingsService settingsService, Clock clock) {
        this.variantRepository = variantRepository;
        this.saleEventRepository = saleEventRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.availabilityService = availabilityService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ForecastResponse forecast() {
        StockAlertSettings settings = settingsService.get();
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock.withZone(SHOP_ZONE));

        List<ProductVariant> variants = variantRepository.findForecastable();
        List<UUID> ids = variants.stream().map(ProductVariant::getId).toList();
        Map<UUID, BigDecimal> available = availabilityService.available(ids);
        Map<UUID, BigDecimal> sold30 = soldSince(now.minus(ForecastMath.WINDOW_DAYS, ChronoUnit.DAYS));
        Map<UUID, BigDecimal> sold7 = soldSince(now.minus(ForecastMath.TREND_DAYS, ChronoUnit.DAYS));
        Map<UUID, BigDecimal> onOrder = new HashMap<>();
        purchaseOrderRepository.outstandingByVariant(OPEN_ORDER_STATUSES)
                .forEach(o -> onOrder.put(o.getVariantId(), o.getQty()));

        List<ForecastRow> rows = variants.stream()
                .map(v -> row(v, available, sold30, sold7, onOrder, settings, now, today))
                .sorted(Comparator
                        .comparingInt((ForecastRow r) -> rank(r.status()))
                        .thenComparing(ForecastRow::daysOfCover, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ForecastRow::productName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new ForecastResponse(now, ForecastMath.WINDOW_DAYS, settings.getLeadTimeDays(),
                settings.getSafetyDays(), summarise(rows), rows);
    }

    /** Just the counts — for the sidebar badge, which asks on every admin page. */
    @Transactional(readOnly = true)
    public Summary summary() {
        return forecast().summary();
    }

    private ForecastRow row(ProductVariant v, Map<UUID, BigDecimal> available, Map<UUID, BigDecimal> sold30,
                            Map<UUID, BigDecimal> sold7, Map<UUID, BigDecimal> onOrder,
                            StockAlertSettings settings, Instant now, LocalDate today) {
        Product product = v.getProduct();
        BigDecimal avail = available.getOrDefault(v.getId(), BigDecimal.ZERO);
        BigDecimal window = sold30.getOrDefault(v.getId(), BigDecimal.ZERO);
        BigDecimal last7 = sold7.getOrDefault(v.getId(), BigDecimal.ZERO);
        BigDecimal ordered = onOrder.getOrDefault(v.getId(), BigDecimal.ZERO);

        ForecastMath.Result r = ForecastMath.compute(avail, window, last7, ordered, v.getReorderLevel(),
                settings.getLeadTimeDays(), settings.getSafetyDays(), today);

        // Few sales, or a variant that only just went on sale, means the average is a guess. Demo data is
        // created "now" but is meant to look like an established shop, so it is not marked for age.
        boolean young = !product.isMockData() && v.getCreatedAt() != null
                && v.getCreatedAt().isAfter(now.minus(14, ChronoUnit.DAYS));
        boolean lowConfidence = window.signum() > 0 && (window.compareTo(BigDecimal.valueOf(3)) < 0 || young);

        return new ForecastRow(v.getId(), product.getId(), product.getName(), v.getVariantName(), v.getSku(),
                avail, window, last7, r.avgPerDay(), r.avgPerDayLast7(), r.trend(), r.daysOfCover(), r.runsOutOn(),
                ordered, r.suggestedRestockQty(), v.getReorderLevel(), r.suggestedReorderLevel(),
                r.belowReorderLevel(), r.status().name(), lowConfidence, product.isMockData());
    }

    private Map<UUID, BigDecimal> soldSince(Instant since) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        saleEventRepository.unitsSoldByVariantSince(since).forEach(s -> result.put(s.getVariantId(), s.getTotalQty()));
        return result;
    }

    private static int rank(String status) {
        return switch (ForecastMath.Status.valueOf(status)) {
            case OUT_NOW -> 0;
            case CRITICAL -> 1;
            case SOON -> 2;
            case OK -> 3;
            case NO_DATA -> 4;
        };
    }

    private static Summary summarise(List<ForecastRow> rows) {
        int outNow = 0, critical = 0, soon = 0, ok = 0, noData = 0, below = 0, attention = 0;
        for (ForecastRow r : rows) {
            switch (ForecastMath.Status.valueOf(r.status())) {
                case OUT_NOW -> outNow++;
                case CRITICAL -> critical++;
                case SOON -> soon++;
                case OK -> ok++;
                case NO_DATA -> noData++;
            }
            if (r.belowReorderLevel()) below++;
            if (needsAttention(r)) attention++;
        }
        return new Summary(outNow, critical, soon, ok, noData, below, attention);
    }

    /** Out now, running out within a week, or at/below its own reorder level. */
    static boolean needsAttention(ForecastRow r) {
        return r.belowReorderLevel() || "OUT_NOW".equals(r.status()) || "CRITICAL".equals(r.status());
    }
}
