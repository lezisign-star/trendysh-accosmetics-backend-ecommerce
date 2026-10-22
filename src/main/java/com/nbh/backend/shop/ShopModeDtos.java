package com.nbh.backend.shop;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public final class ShopModeDtos {

    private ShopModeDtos() {}

    /** What the storefront reads (public): nothing but the mode. */
    public record ShopModeDto(OrderingMode orderingMode) {}

    /** What the settings screen reads: the mode, who last changed it and when (null until someone has). */
    public record ShopModeAdminDto(OrderingMode orderingMode, String changedBy, Instant changedAt) {
        static ShopModeAdminDto from(ShopSettings settings) {
            return new ShopModeAdminDto(settings.getOrderingMode(), settings.getChangedBy(),
                    settings.getChangedBy() == null ? null : settings.getUpdatedAt());
        }
    }

    public record SetShopModeRequest(@NotNull OrderingMode orderingMode) {}
}
