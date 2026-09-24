package com.nbh.backend.stock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Posts an ADJUSTMENT: qtyChange positive = SHOP <- ADJUSTMENT (a found/
 * corrected surplus); negative = SHOP -> ADJUSTMENT (a write-off/shrinkage).
 * unitCost is required only when qtyChange is positive (an inbound line needs a
 * cost to update the moving average) — the API deliberately doesn't invent one.
 */
public record AdjustStockRequest(
        @NotNull UUID variantId,
        @NotNull String qtyChange,
        String unitCost,
        @NotBlank String reason
) {}
