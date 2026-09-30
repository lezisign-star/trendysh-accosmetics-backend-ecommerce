package com.nbh.backend.catalog.dto;

import java.time.Instant;

/** Status of the developer-only demo/mock catalogue — see MockDataSeederService. */
public record MockDataStatusDto(
        boolean enabled,
        Instant generatedAt,
        int productCount,
        int kitCount,
        int professionCount,
        /** The synthetic orders review/sale-event generation needs as an FK target — never real
         *  checkouts, and never shown to a customer, but real rows in the admin orders list
         *  while enabled (see CustomerOrderRepository.search's mock-data clause). */
        int orderCount) {
}
