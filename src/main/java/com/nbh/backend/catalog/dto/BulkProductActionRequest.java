package com.nbh.backend.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The admin product list's bulk-action bar (Phase 3, 2026-09-24) — one action across several products at once. */
public record BulkProductActionRequest(
        Action action,
        List<UUID> productIds,
        /** ADD_CATEGORIES / REPLACE_CATEGORIES only. */
        List<UUID> categoryIds,
        /** ADJUST_PRICE only — e.g. 10 for +10%, -15 for -15%. Rounded half-up to whole shillings. */
        BigDecimal percent,
        /** ADJUST_PRICE only — variant ids to leave untouched even though their product was selected. */
        List<UUID> excludedVariantIds,
        /** MOVE_CATEGORY only — each product leaves this category and is filed under {@code toCategoryId} instead. */
        UUID fromCategoryId,
        /** MOVE_CATEGORY only. */
        UUID toCategoryId
) {
    public enum Action { PUBLISH, UNPUBLISH, DELETE, RESTORE, ADD_CATEGORIES, REPLACE_CATEGORIES, MOVE_CATEGORY, ADJUST_PRICE }
}
