package com.nbh.backend.stock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Posts a GOODS_RECEIPT: SUPPLIER -> SHOP, one line per SKU. */
public record ReceiveStockRequest(
        String notes,
        @NotEmpty @Valid List<Line> lines
) {
    public record Line(
            @NotNull UUID variantId,
            @NotNull String qty,
            @NotNull String unitCost
    ) {}
}
