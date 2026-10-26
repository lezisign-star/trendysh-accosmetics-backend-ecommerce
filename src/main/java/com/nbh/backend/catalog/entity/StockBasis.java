package com.nbh.backend.catalog.entity;

/**
 * Where an option's stock number comes from (V78, owner 2026-10-26). {@code NONE}: never counted, set or received, so the
 * shop has no number it stands behind (such an option can be sold without a stock limit when a "sell without stock" switch
 * covers it). {@code ESTIMATED}: set in bulk to take products live, shown to staff as estimated. {@code COUNTED}: typed by
 * staff for that option, received, or counted in a stock take.
 */
public enum StockBasis {
    NONE,
    ESTIMATED,
    COUNTED
}
