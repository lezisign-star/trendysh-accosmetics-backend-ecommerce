package com.nbh.backend.cart.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateCartItemRequest(@NotNull BigDecimal qty) {}
