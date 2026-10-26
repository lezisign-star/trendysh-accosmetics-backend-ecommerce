package com.nbh.backend.cart.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AddToCartRequest(
        @NotNull UUID variantId,
        @NotNull BigDecimal qty,
        /**
         * Only for a package: what the shopper picked instead of what it comes with, as default variant id -> the variant they
         * chose (another colour or size of the same product). Left out or empty = the package as it comes.
         * {@code kitChoices} is what a page opened before packages were called packages still sends: without the alias its
         * picks would be dropped silently and the order would get the default colours. Remove it once those pages are gone.
         */
        @JsonAlias("kitChoices") java.util.Map<UUID, UUID> packageChoices
) {
    public AddToCartRequest(UUID variantId, BigDecimal qty) {
        this(variantId, qty, null);
    }
}
