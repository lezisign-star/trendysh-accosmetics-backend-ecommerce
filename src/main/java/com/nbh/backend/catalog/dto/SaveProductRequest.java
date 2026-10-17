package com.nbh.backend.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one-page product editor's single Save (Phase 3, 2026-09-24) — everything about a product and
 * its variants in one request, replacing the old create/update-product + add/update-variant dance.
 * Nothing here is {@code @NotBlank}: a draft can be saved in any state of completeness (founder
 * decision — "drafts save anytime, only warn at publish"); {@link ProductDto#warnings()} in the
 * response tells the caller what's still missing, it never blocks the save itself.
 *
 * {@code variants} is the product's full variant list in display order — a variant missing from
 * this list on an update is soft-deleted, one already-saved with an {@code id} is updated in
 * place, one with no {@code id} is created. Order in the list becomes {@code sortOrder}.
 */
public record SaveProductRequest(
        /** The product's {@code updatedAt} this edit started from — a mismatch means someone else changed it first. Null skips the check (new drafts, or a caller that doesn't care). */
        Instant expectedUpdatedAt,
        String name,
        String description,
        String shortDescription,
        String ingredients,
        String howToUse,
        /** In display order; the first is the "primary" category (breadcrumbs, SKU prefix). */
        List<UUID> categoryIds,
        boolean published,
        Boolean isPopular,
        Boolean isNewArrival,
        List<VariantInput> variants
) {
    public record VariantInput(
            /** Null for a new variant. */
            UUID id,
            String variantName,
            String description,
            /** A decimal string, or null/blank for "no price yet". */
            String sellingPrice,
            boolean isActive,
            /**
             * The variant's colour and size (V63). Null = leave them as they are, which is what an editor from before
             * this field sends, so saving from it never wipes a generated variant's colour or size. Sent = exactly
             * these (blank clears one).
             */
            VariantOptions options,
            /**
             * A category of its own (V68), read only by the single-variant save and only when its range lets categories
             * differ; null there = the range's categories.
             */
            UUID categoryId
    ) {
        /** The four-argument form editors and seeders used before options existed. */
        public VariantInput(UUID id, String variantName, String description, String sellingPrice, boolean isActive) {
            this(id, variantName, description, sellingPrice, isActive, null, null);
        }
    }

    public record VariantOptions(
            String color,
            String size,
            /** "#RRGGBB" (with or without the #); blank = none. Anything else is refused. */
            String swatchHex,
            /** A {@code ColorFamily} name, any case; blank = work it out from the dot. Anything else is refused. */
            String colorFamily
    ) {}
}
