package com.nbh.backend.catalog.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Update of a product's own fields. {@code name}, {@code brandId} and category/subcategory are
 * full-replace — always send the value you want, {@code null} clears it (this is the established
 * contract those fields already had). The four text fields ({@code shortDescription},
 * {@code description}, {@code ingredients}, {@code howToUse}) and the two merchandising flags are
 * merge-only: {@code null} means "leave this field as it is", not "clear it" — found live
 * 2026-09-22 that the admin's Quick-edit panel only ever sends a handful of fields, and a plain
 * full-replace was silently wiping the other text fields to null on every quick save. Variants are
 * managed separately (see {@code UpdateVariantRequest} / adding a new one).
 */
public record UpdateProductRequest(
        @NotBlank String name,
        UUID brandId,
        UUID categoryId,
        UUID subcategoryId,
        String shortDescription,
        String description,
        String ingredients,
        String howToUse,
        Boolean isPopular,
        Boolean isNewArrival
) {}
