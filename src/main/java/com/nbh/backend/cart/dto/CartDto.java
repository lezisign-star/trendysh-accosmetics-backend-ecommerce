package com.nbh.backend.cart.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CartDto(
        UUID id,
        String sessionId,
        List<CartItemDto> items,
        BigDecimal subtotal
) {
    public static CartDto from(com.nbh.backend.cart.entity.Cart cart, List<CartItemDto> items) {
        BigDecimal subtotal = items.stream().map(CartItemDto::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartDto(cart.getId(), cart.getSessionId(), items, subtotal);
    }
}
