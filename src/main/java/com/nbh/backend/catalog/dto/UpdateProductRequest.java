package com.nbh.backend.catalog.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** Full-replace update of a product's own fields — variants are managed separately (see UpdateVariantRequest / adding a new one). */
public record UpdateProductRequest(
        @NotBlank String name,
        UUID brandId,
        UUID categoryId,
        UUID subcategoryId,
        String shortDescription,
        String description,
        String ingredients,
        String howToUse
) {}
