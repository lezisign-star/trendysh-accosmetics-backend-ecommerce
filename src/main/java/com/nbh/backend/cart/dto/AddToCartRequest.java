package com.nbh.backend.cart.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AddToCartRequest(
        @NotNull UUID variantId,
        @NotNull BigDecimal qty,
        /**
         * Only for a kit: what the shopper picked instead of what it comes with, as default variant id -> the variant they
         * chose (another colour or size of the same product). Left out or empty = the kit as it comes.
         */
        java.util.Map<UUID, UUID> kitChoices
) {
    public AddToCartRequest(UUID variantId, BigDecimal qty) {
        this(variantId, qty, null);
    }
}
