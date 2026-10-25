package com.nbh.backend.stock.insights;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.insights.StockInsightsDtos.ApplySuggestedResult;
import com.nbh.backend.stock.insights.StockInsightsDtos.ForecastRow;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sets a variant's reorder level — the stock quantity at or below which it counts as "low" (the Stock
 * page filter, the product list badge) and at which the low-stock alert email fires. Until 2026-09-30
 * nothing anywhere could write this, so none of that ever triggered.
 */
@Service
public class ReorderLevelService {

    private final ProductVariantRepository variantRepository;
    private final StockForecastService forecastService;

    public ReorderLevelService(ProductVariantRepository variantRepository, StockForecastService forecastService) {
        this.variantRepository = variantRepository;
        this.forecastService = forecastService;
    }

    /** @param reorderLevel null clears it (no alert for this variant) */
    @Transactional
    public BigDecimal set(UUID variantId, BigDecimal reorderLevel) {
        if (reorderLevel != null && reorderLevel.signum() < 0) {
            throw new IllegalArgumentException("A reorder level can't be negative");
        }
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new EntityNotFoundException("No variant: " + variantId));
        if (variant.isPackage()) {
            throw new IllegalArgumentException("Packages hold no stock of their own — set reorder levels on their components");
        }
        variant.setReorderLevel(reorderLevel == null ? null : reorderLevel.setScale(3, RoundingMode.HALF_UP));
        variantRepository.save(variant);
        return variant.getReorderLevel();
    }

    /** Sets each given variant's reorder level to what the forecast suggests; variants with no sales history are skipped. */
    @Transactional
    public ApplySuggestedResult applySuggested(List<UUID> variantIds) {
        Set<UUID> wanted = Set.copyOf(variantIds);
        Map<UUID, ForecastRow> rows = forecastService.forecast().rows().stream()
                .filter(r -> wanted.contains(r.variantId()))
                .collect(Collectors.toMap(ForecastRow::variantId, r -> r));
        int updated = 0;
        for (UUID id : wanted) {
            ForecastRow row = rows.get(id);
            if (row == null || row.suggestedReorderLevel() == null) continue;
            set(id, row.suggestedReorderLevel());
            updated++;
        }
        return new ApplySuggestedResult(updated, wanted.size() - updated);
    }
}
