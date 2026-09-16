package com.nbh.backend.branch.entity;

public enum LocationType {
    SALES_FLOOR(true),
    BACKSTORE(true),
    DAMAGED(true),
    IN_TRANSIT(false),
    SUPPLIER(false),
    CUSTOMER(false),
    ADJUSTMENT(false);

    private final boolean internal;

    LocationType(boolean internal) {
        this.internal = internal;
    }

    /** Only an internal location has a real, physically countable shelf. */
    public boolean isInternal() {
        return internal;
    }
}
