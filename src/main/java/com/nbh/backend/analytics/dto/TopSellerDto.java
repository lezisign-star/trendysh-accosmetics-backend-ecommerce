package com.nbh.backend.analytics.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TopSellerDto(UUID productId, String productName, BigDecimal unitsSold) {}
