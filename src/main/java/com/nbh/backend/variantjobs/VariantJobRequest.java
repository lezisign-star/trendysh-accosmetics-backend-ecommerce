package com.nbh.backend.variantjobs;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the variant wizard sends (VARIANTS_PLAN.md 3.4 and 3.7). Colours and sizes are lists in shop order; either may be
 * empty, not both. Prices come from {@link Pricing} (a rule, then each colour's +/- and single-cell prices on top) and
 * the server works every cell out itself; a {@code cells[].price} that disagrees with that is refused, so a screen that
 * did its sums wrong can never put a wrong price in the shop.
 */
public record VariantJobRequest(
        UUID productId,
        /** The existing variant whose photo and details the new ones are made from; null = the product's first. */
        UUID baseVariantId,
        List<ColorIn> colors,
        List<SizeIn> sizes,
        /** "Every size comes in every colour" (the wizard's default). Ticked with something left out is refused. */
        Boolean everyCombination,
        /** Per combination: left out, its own price (PER_CELL), opening stock and cost. Missing cells are included. */
        List<CellIn> cells,
        Pricing pricing,
        /** "Only some colours come in this size". */
        List<SizeAvailability> sizeAvailability,
        /** What the existing base variant itself is, when it has no colour or size yet. */
        BaseOption baseOption,
        boolean approveFirst,
        /** Extra notes for the descriptions and label text, as the old generator's prompt. */
        String prompt,
        /** How the photos should look: where the colour shows, and a real photo to follow (all colours). Optional. */
        PhotoGuide photoGuide,
        /** Colours that look different from the rest (a black-bottle series): their own note and real photo. */
        List<PhotoGroup> photoGroups,
        /** Photos staff tried and approved before Generate, reused as that colour's photo instead of a new AI call. */
        List<TrialPhoto> trialPhotos,
        /** Set only on a "Redo photos" job: these existing variants get new photos; nothing else is made. */
        List<UUID> redoVariantIds
) {
    /** {@code note}: staff's words, e.g. "only the round top of the cap shows the colour; the bottle stays white". */
    /**
     * @param maskUrl a stored picture the shape of the product's photo, white where the colour may change; the rest of
     *                the photo is put back as the product's own ({@link MaskComposer})
     * @param maskPhotoUrl the photo the part was marked on; the mark is used only on that photo
     */
    public record PhotoGuide(String note, String referenceUrl, String maskUrl, String maskPhotoUrl) {}

    /**
     * @param maskUrl the part of the photo that may change for this group's colours, replacing the general one (a
     *                black-bottle series changes the whole bottle, not only the cap)
     */
    public record PhotoGroup(List<String> colors, String note, String referenceUrl, String maskUrl,
                             String maskPhotoUrl) {}

    public record TrialPhoto(String color, String url) {}

    /** The guide, groups and approved photos together, as the photo steps use them. */
    public PhotoGuides guides() {
        return new PhotoGuides(photoGuide, photoGroups, trialPhotos);
    }

    public boolean isRedo() {
        return redoVariantIds != null && !redoVariantIds.isEmpty();
    }

    public record ColorIn(String name, String hex, String family) {}

    public record SizeIn(String name, String labelText, UUID photoImageId) {}

    public record CellIn(String color, String size, Boolean include, BigDecimal price, BigDecimal openingStock,
                         BigDecimal unitCost) {}

    public enum Mode { SAME, STEP_UP, STEP_DOWN, PER_SIZE, PER_CELL }

    public record Pricing(Mode mode, BigDecimal price, BigDecimal start, BigDecimal step,
                          Map<String, BigDecimal> sizePrices, List<ColorAdjustment> colorAdjustments,
                          List<CellOverride> cellOverrides) {}

    public record ColorAdjustment(String color, BigDecimal amount) {}

    public record CellOverride(String color, String size, BigDecimal price) {}

    public record SizeAvailability(String size, List<String> onlyColors) {}

    public record BaseOption(String color, String size) {}
}
