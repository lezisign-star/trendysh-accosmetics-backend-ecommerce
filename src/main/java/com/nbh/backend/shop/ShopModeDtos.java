package com.nbh.backend.shop;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public final class ShopModeDtos {

    private ShopModeDtos() {}

    /**
     * What the storefront reads (public): the mode, and the WhatsApp number orders and questions go to (the one saved in
     * Admin > Payment settings, else the shop's default), so every shopper's browser uses the number the owner set.
     */
    public record ShopModeDto(OrderingMode orderingMode, String supportWhatsapp) {}

    /** What the settings screen reads: the mode, who last changed it and when (null until someone has). */
    public record ShopModeAdminDto(OrderingMode orderingMode, String changedBy, Instant changedAt) {
        static ShopModeAdminDto from(ShopSettings settings) {
            return new ShopModeAdminDto(settings.getOrderingMode(), settings.getChangedBy(),
                    settings.getChangedBy() == null ? null : settings.getUpdatedAt());
        }
    }

    public record SetShopModeRequest(@NotNull OrderingMode orderingMode) {}
}
