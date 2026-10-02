package com.nbh.backend.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * One return can cover several order items (a customer sending back a whole order) but shares
 * one reason/notes — same "one reason per document" convention as {@code WriteOffRequest}.
 * {@code restock}, per line: true puts the returned units back into sellable SHOP stock (valued
 * at what they originally cost, via {@code CustomerOrderItem.unitCostAtSale} — not today's
 * average), false sends them straight to ADJUSTMENT (damaged, not resellable) without ever
 * touching on-hand.
 */
public record OrderReturnRequest(
        @NotEmpty @Valid List<Line> lines,
        String reason,
        String notes
) {
    public record Line(@NotNull UUID itemId, @NotNull String qty, boolean restock) {}
}
