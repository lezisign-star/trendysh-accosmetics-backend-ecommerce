package com.nbh.backend.order.dto;

import java.util.UUID;

public record CheckoutResponseDto(OrderDto order, UUID paymentRecordId) {}
