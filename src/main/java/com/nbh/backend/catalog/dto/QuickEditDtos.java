package com.nbh.backend.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The quick-edit table: every option's numbers in one list, changed in place (owner, 2026-10-31). See {@code QuickEditService}. */
public final class QuickEditDtos {

    private QuickEditDtos() {}

    /**
     * One option as the table shows it. {@code sharedPrice} is set when the option belongs to a confirmed range whose
     * options all cost the same: its price is then changed on the range, not here. {@code dealPrice}/{@code dealEndsAt}
     * are a running flash deal only (an ended one is not shown), for information.
     */
    public record Row(
            UUID variantId,
            UUID productId,
            String productName,
            /** The option on its own ("Ruby Red / 50ml"), or null when the product has just the one. */
            String option,
            String sku,
            String categoryName,
            boolean active,
            boolean productPublished,
            BigDecimal sellingPrice,
            BigDecimal wholesalePrice,
            Integer wholesaleMinQty,
            BigDecimal reorderLevel,
            BigDecimal dealPrice,
            Instant dealEndsAt,
            BigDecimal onHandQty,
            BigDecimal reservedQty,
            boolean untracked,
            BigDecimal sharedPrice
    ) {}

    /** A number as the table showed it ({@code from}) and what staff typed ({@code to}; null clears it where that is allowed). */
    public record Edit(BigDecimal from, BigDecimal to) {}

    /** The wholesale tier as shown and as typed: both {@code to} values, or neither (no tier). */
    public record WholesaleEdit(BigDecimal fromPrice, Integer fromMinQty, BigDecimal toPrice, Integer toMinQty) {}

    /**
     * One row's changes, saved together or not at all. Send only what changed. {@code onHand} is the count on the shelf
     * now, posted to the stock ledger like any other count; {@code reason} goes on that stock document.
     */
    public record SaveRow(Edit sellingPrice, WholesaleEdit wholesale, Edit reorderLevel, BigDecimal onHand, String reason) {}

    /** The row as saved, what else changed because of it (a sale or wholesale tier cleared), and the stock document, if any. */
    public record SaveResult(Row row, List<String> warnings, String stockDocumentNo) {}
}
