package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Whose photo is it? The owner's rule (2026-10-07): "we must never have shared photos among the related products".
 *
 * <p>Every photo belongs to exactly ONE sellable option (a variant). A shopper who opens an option sees that
 * option's photos and nothing else; the way to see a sibling's photos is to open the sibling. A photo still filed at
 * product level (variant null, the old "whole product" kind) counts only when the product has a single option,
 * where it can only mean that option's own. On a product with several options it is "unassigned": the shop does not
 * show it anywhere until staff give it to one option.
 *
 * <p>Each owner has its own main photo ({@code isPrimary}); choosing one never touches another owner's.
 *
 * <p>Everything that picks a photo for display (the shop's pages and cards, package tiles, the admin list) goes through
 * here, so the rule lives in exactly one place.
 */
public final class PhotoOwnership {

    private PhotoOwnership() {
    }

    /** The option a photo is filed under, or null when it sits at product level. */
    public static UUID ownerOf(ProductImage image) {
        return image.getVariant() == null ? null : image.getVariant().getId();
    }

    /** True when the photo is shown on this option's page. */
    public static boolean showsOn(ProductImage image, UUID variantId, boolean singleOption) {
        UUID owner = ownerOf(image);
        return owner == null ? singleOption : owner.equals(variantId);
    }

    /** The photos with the same owner (null = the product-level, unassigned ones), in the order given. */
    public static List<ProductImage> sameOwner(Collection<ProductImage> images, UUID owner) {
        return images.stream().filter(i -> Objects.equals(ownerOf(i), owner)).toList();
    }

    /** What a shopper sees on one option's page: its main photo first, then the rest in gallery order. */
    public static List<ProductImage> galleryFor(UUID variantId, boolean singleOption, Collection<ProductImage> imagesOfProduct) {
        return imagesOfProduct.stream()
                .filter(i -> showsOn(i, variantId, singleOption))
                .sorted(mainFirst())
                .toList();
    }

    /** The option's cover photo: the first of its gallery. */
    public static Optional<ProductImage> coverFor(UUID variantId, boolean singleOption, Collection<ProductImage> imagesOfProduct) {
        return galleryFor(variantId, singleOption, imagesOfProduct).stream().findFirst();
    }

    /**
     * The photo that stands for a whole product in a staff list: the first option (in order) that has a photo, else
     * an unassigned one so a product that only has those is not shown blank.
     */
    public static Optional<ProductImage> productCover(List<UUID> optionIdsInOrder, boolean singleOption,
                                                      Collection<ProductImage> imagesOfProduct) {
        for (UUID optionId : optionIdsInOrder) {
            Optional<ProductImage> cover = coverFor(optionId, singleOption, imagesOfProduct);
            if (cover.isPresent()) {
                return cover;
            }
        }
        return imagesOfProduct.stream().filter(i -> ownerOf(i) == null).sorted(mainFirst()).findFirst();
    }

    /** How many live options each product has; a product with one is "single option". */
    public static Map<UUID, Long> optionCounts(Collection<ProductVariant> liveVariants) {
        return liveVariants.stream().collect(Collectors.groupingBy(v -> v.getProduct().getId(), Collectors.counting()));
    }

    public static boolean isSingleOption(Map<UUID, Long> optionCountByProduct, UUID productId) {
        return optionCountByProduct.getOrDefault(productId, 1L) <= 1;
    }

    /** Each option's cover photo URL (an option with no photo of its own is simply absent). */
    public static Map<UUID, String> coverUrlByVariant(Collection<ProductVariant> variants, Collection<ProductImage> images,
                                                      Map<UUID, Long> optionCountByProduct) {
        Map<UUID, List<ProductImage>> byProduct = images.stream()
                .collect(Collectors.groupingBy(i -> i.getProduct().getId()));
        Map<UUID, String> result = new HashMap<>();
        for (ProductVariant variant : variants) {
            UUID productId = variant.getProduct().getId();
            coverFor(variant.getId(), isSingleOption(optionCountByProduct, productId), byProduct.getOrDefault(productId, List.of()))
                    .ifPresent(cover -> result.put(variant.getId(), cover.getUrl()));
        }
        return result;
    }

    private static Comparator<ProductImage> mainFirst() {
        return Comparator.comparing(ProductImage::isPrimary).reversed().thenComparingInt(ProductImage::getSortOrder);
    }
}
