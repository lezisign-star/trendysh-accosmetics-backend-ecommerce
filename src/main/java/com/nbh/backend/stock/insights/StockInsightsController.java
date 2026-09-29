package com.nbh.backend.stock.insights;

import com.nbh.backend.stock.insights.StockInsightsDtos.ApplySuggestedRequest;
import com.nbh.backend.stock.insights.StockInsightsDtos.ApplySuggestedResult;
import com.nbh.backend.stock.insights.StockInsightsDtos.ForecastResponse;
import com.nbh.backend.stock.insights.StockInsightsDtos.ReorderLevelRequest;
import com.nbh.backend.stock.insights.StockInsightsDtos.ReorderLevelResult;
import com.nbh.backend.stock.insights.StockInsightsDtos.Summary;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The Forecast tab under Inventory: what runs out and when, plus setting reorder levels. */
@RestController
@RequestMapping("/api/v1/stock/insights")
public class StockInsightsController {

    private final StockForecastService forecastService;
    private final ReorderLevelService reorderLevelService;

    public StockInsightsController(StockForecastService forecastService, ReorderLevelService reorderLevelService) {
        this.forecastService = forecastService;
        this.reorderLevelService = reorderLevelService;
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping("/forecast")
    public ForecastResponse forecast() {
        return forecastService.forecast();
    }

    /** The counts alone — the admin sidebar badge asks for this on every page. */
    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping("/summary")
    public Summary summary() {
        return forecastService.summary();
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PutMapping("/variants/{variantId}/reorder-level")
    public ReorderLevelResult setReorderLevel(@PathVariable UUID variantId, @RequestBody ReorderLevelRequest body) {
        return new ReorderLevelResult(variantId, reorderLevelService.set(variantId, body.reorderLevel()));
    }

    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PostMapping("/reorder-levels/apply-suggested")
    public ApplySuggestedResult applySuggested(@Valid @RequestBody ApplySuggestedRequest body) {
        return reorderLevelService.applySuggested(body.variantIds());
    }
}
