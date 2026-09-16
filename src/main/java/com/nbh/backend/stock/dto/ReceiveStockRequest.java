package com.nbh.backend.stock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Posts a GOODS_RECEIPT: SUPPLIER -> the given internal location, one line per SKU. */
public record ReceiveStockRequest(
        @NotNull UUID locationId,
        String notes,
        @NotEmpty @Valid List<Line> lines
) {
    public record Line(
            @NotNull UUID variantId,
            @NotNull String qty,
            @NotNull String unitCost
    ) {}
}
