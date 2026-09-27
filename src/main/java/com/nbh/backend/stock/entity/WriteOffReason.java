package com.nbh.backend.stock.entity;

/** Phase 2 (2026-09-27): why stock left SHOP with nothing sold and nothing returned to a supplier. */
public enum WriteOffReason {
    DAMAGED,
    EXPIRED,
    THEFT_LOSS,
    TESTER_SAMPLE,
    OWN_USE,
    OTHER
}
