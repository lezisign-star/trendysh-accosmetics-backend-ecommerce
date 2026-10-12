package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The cover photo each option shows in the shop right now, for code that needs a handful of variants' covers without
 * being a catalogue read itself (an order line freezing the photo it was ordered with). The rule is
 * {@link PhotoOwnership}'s, the same one the shop's cards use; this only does the two lookups it needs.
 */
@Component
public class VariantCovers {

    private final ProductImageRepository imageRepository;
    private final ProductVariantRepository variantRepository;

    public VariantCovers(ProductImageRepository imageRepository, ProductVariantRepository variantRepository) {
        this.imageRepository = imageRepository;
        this.variantRepository = variantRepository;
    }

    /** Keyed by variant id: that option's own cover photo URL, or absent when it has none. */
    public Map<UUID, String> urlsFor(Collection<ProductVariant> variants) {
        if (variants.isEmpty()) {
            return Map.of();
        }
        List<UUID> productIds = variants.stream().map(v -> v.getProduct().getId()).distinct().toList();
        List<ProductImage> images = imageRepository.findByProductIdInOrderBySortOrderAsc(productIds);
        Map<UUID, Long> optionCounts = PhotoOwnership.optionCounts(
                variantRepository.findByProductIdInAndDeletedFalse(productIds));
        return PhotoOwnership.coverUrlByVariant(variants, images, optionCounts);
    }
}
