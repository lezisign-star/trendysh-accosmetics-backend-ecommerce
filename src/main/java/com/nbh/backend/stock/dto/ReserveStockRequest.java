package com.nbh.backend.stock.dto;

import com.nbh.backend.stock.entity.StockReservation;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReserveStockRequest(
        @NotNull UUID variantId,
        @NotNull String qty,
        @NotNull StockReservation.ReservationSource source,
        @NotNull String referenceType,
        @NotNull UUID referenceId,
        Integer expiresInSeconds
) {}
