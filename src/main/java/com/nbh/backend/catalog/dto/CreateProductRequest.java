package com.nbh.backend.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record CreateProductRequest(
        @NotBlank String name,
        UUID brandId,
        UUID categoryId,
        UUID subcategoryId,
        String shortDescription,
        String description,
        String ingredients,
        String howToUse,
        Boolean isPopular,
        Boolean isNewArrival,
        @NotEmpty @Valid List<CreateVariantRequest> variants
) {
    public record CreateVariantRequest(
            @NotBlank String sku,
            @NotBlank String variantName,
            @NotBlank String sellingPrice,   // string in/out to avoid float rounding surprises over JSON
            String compareAtPrice,
            String standardCost,
            String uom,
            String vatRate,
            boolean vatExempt,
            String reorderLevel,
            List<UUID> attributeValueIds,
            List<String> barcodes
    ) {}
}
