package com.nbh.backend.stock.entity;

/**
 * Phase 2 (2026-09-27): DRAFT -&gt; APPLIED is the v1 flow (lines get recorded while DRAFT, then
 * applied in one step); IN_PROGRESS/SUBMITTED are reserved for a later, more granular workflow
 * (e.g. a supervisor sign-off step between counting and applying) and aren't produced yet.
 * CANCELLED is only reachable from DRAFT — an APPLIED count already posted real stock.
 */
public enum StockCountStatus {
    DRAFT,
    IN_PROGRESS,
    SUBMITTED,
    APPLIED,
    CANCELLED
}
