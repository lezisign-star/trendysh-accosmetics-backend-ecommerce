package com.nbh.backend.catalog.dto;

/**
 * One colour or size in a product's picker (V63), in display order. {@code swatchHex} and {@code colorFamily} (a
 * {@code ColorFamily} name) are for colours only; null for a size, or a colour with no dot.
 */
public record CatalogOptionDto(String value, String swatchHex, String colorFamily, int position) {}
