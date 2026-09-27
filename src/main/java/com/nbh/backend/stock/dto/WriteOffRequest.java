package com.nbh.backend.stock.dto;

import com.nbh.backend.stock.entity.WriteOffReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Phase 2 (2026-09-27): one write-off can cover several variants (a damaged shelf, an expired
 * batch) but shares one reasonCode and one note — a batch found for two different reasons is
 * two write-offs, not one, so the reason stays meaningful for shrinkage analytics.
 */
public record WriteOffRequest(
        @NotEmpty @Valid List<Line> lines,
        @NotNull WriteOffReason reasonCode,
        String notes
) {
    public record Line(@NotNull UUID variantId, @NotNull String qty) {}
}
