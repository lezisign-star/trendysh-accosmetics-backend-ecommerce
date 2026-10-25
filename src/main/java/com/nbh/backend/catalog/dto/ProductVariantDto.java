package com.nbh.backend.catalog.dto;

import com.nbh.backend.catalog.entity.ProductVariant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code availableQty} is what can be sold right now (on hand minus reserved, across physical
 * locations). It is filled in by the admin list/detail endpoints and null elsewhere (e.g. right
 * after creating a variant, where it is simply zero).
 *
 * {@code dealPrice}/{@code dealEndsAt} and {@code wholesaleMinQty}/{@code wholesalePrice} are the
 * raw stored values, not resolved through {@code EffectivePriceResolver} — this is the admin/staff
 * view of the variant, which needs to see and edit exactly what's set, including an already-expired
 * deal it forgot to clear.
 */
public record ProductVariantDto(
        UUID id,
        String sku,
        String variantName,
        String description,
        int sortOrder,
        BigDecimal sellingPrice,
        BigDecimal compareAtPrice,
        BigDecimal standardCost,
        String uom,
        BigDecimal vatRate,
        boolean vatExempt,
        BigDecimal reorderLevel,
        boolean isActive,
        BigDecimal availableQty,
        BigDecimal dealPrice,
        Instant dealEndsAt,
        Integer wholesaleMinQty,
        BigDecimal wholesalePrice,
        /** The short option the picker and orders show ("Ruby Red / 50ml"); null = the name is used. */
        String optionLabel,
        String optionColor,
        String optionSize,
        String swatchHex,
        /** Staff's choice of {@code ColorFamily} by name; null = none chosen (see {@code suggestedColorFamily}). */
        String colorFamily,
        /** The family the dot looks like, what the shop uses when staff chose none; null without a dot. */
        String suggestedColorFamily,
        Integer sizePosition,
        /** The variant that stands for a package: such a product is changed under Packages, never in the product editor. */
        boolean isPackage
) {
    public static ProductVariantDto from(ProductVariant v) {
        return from(v, null);
    }

    public static ProductVariantDto from(ProductVariant v, BigDecimal availableQty) {
        return new ProductVariantDto(
                v.getId(), v.getSku(), v.getVariantName(), v.getDescription(), v.getSortOrder(),
                v.getSellingPrice(), v.getCompareAtPrice(),
                v.getStandardCost(), v.getUom(), v.getVatRate(), v.isVatExempt(), v.getReorderLevel(), v.isActive(),
                availableQty, v.getDealPrice(), v.getDealEndsAt(),
                v.getWholesaleMinQty(), v.getWholesalePrice(),
                v.getOptionLabel(), v.getOptionColor(), v.getOptionSize(), v.getSwatchHex(), v.getColorFamily(),
                v.getSwatchHex() == null ? null : nameOf(com.nbh.backend.catalog.entity.ColorFamily.fromHex(v.getSwatchHex())),
                v.getSizePosition(), v.isPackage());
    }

    private static String nameOf(com.nbh.backend.catalog.entity.ColorFamily f) {
        return f == null ? null : f.name();
    }
}
