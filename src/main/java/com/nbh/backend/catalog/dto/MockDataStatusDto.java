package com.nbh.backend.catalog.dto;

import java.time.Instant;

/** Status of the developer-only demo/mock catalogue — see MockDataSeederService. */
public record MockDataStatusDto(
        boolean enabled,
        Instant generatedAt,
        int productCount,
        int kitCount,
        int professionCount) {
}
