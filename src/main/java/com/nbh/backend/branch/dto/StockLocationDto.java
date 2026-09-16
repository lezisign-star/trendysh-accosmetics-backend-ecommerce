package com.nbh.backend.branch.dto;

import com.nbh.backend.branch.entity.StockLocation;

import java.util.UUID;

public record StockLocationDto(
        UUID id,
        UUID branchId,
        String code,
        String name,
        String locationType,
        boolean isInternal
) {
    public static StockLocationDto from(StockLocation location) {
        return new StockLocationDto(
                location.getId(),
                location.getBranch() != null ? location.getBranch().getId() : null,
                location.getCode(),
                location.getName(),
                location.getLocationType().name(),
                location.isInternal());
    }
}
