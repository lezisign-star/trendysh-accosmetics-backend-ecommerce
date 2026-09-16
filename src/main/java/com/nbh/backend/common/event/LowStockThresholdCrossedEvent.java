package com.nbh.backend.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published by {@code StockPostingService.applyMovement} — edge-triggered,
 * not level-triggered: fires exactly once when a movement takes on-hand from
 * at-or-above {@code reorderLevel} to below it, never on every subsequent
 * sale of an already-low SKU (which would be dozens of identical alerts per
 * hour during a promotion — see §2.6's design note). {@code reorderLevel} is
 * echoed on the event rather than requiring a listener to re-look it up.
 */
public record LowStockThresholdCrossedEvent(
        UUID variantId,
        String sku,
        UUID locationId,
        BigDecimal onHandBefore,
        BigDecimal onHandAfter,
        BigDecimal reorderLevel
) {}
