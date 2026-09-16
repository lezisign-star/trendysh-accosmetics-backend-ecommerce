package com.nbh.backend.cart.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AddToCartRequest(
        @NotNull UUID variantId,
        @NotNull BigDecimal qty
) {}
