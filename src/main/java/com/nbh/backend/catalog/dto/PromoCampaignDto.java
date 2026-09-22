package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.PromoCampaign;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PromoCampaignDto(
        UUID id,
        String name,
        BigDecimal percentOff,
        Instant startsAt,
        Instant endsAt,
        boolean isActive,
        boolean isCurrentlyActive
) {
    public static PromoCampaignDto from(PromoCampaign c, Instant now) {
        return new PromoCampaignDto(c.getId(), c.getName(), c.getPercentOff(), c.getStartsAt(), c.getEndsAt(),
                c.isActive(), c.isCurrentlyActive(now));
    }
}
