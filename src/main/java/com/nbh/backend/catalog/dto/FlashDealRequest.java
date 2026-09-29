package com.nbh.backend.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Put these products on flash deal": a percentage off every sellable variant until a deadline —
 * the shop owner thinks in "20% off until Friday", not in a deal price per variant. See
 * {@code FlashDealBulkService}.
 */
public record FlashDealRequest(
        @NotEmpty List<UUID> productIds,
        @NotNull @Min(1) @Max(90) Integer percentOff,
        @NotNull Instant endsAt) {

    public record End(@NotEmpty List<UUID> productIds) {}
}
