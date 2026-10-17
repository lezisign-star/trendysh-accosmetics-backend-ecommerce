package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.variantgen.VariantCombos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The shop's cards crop a photo around the part that changes (V66, owner 2026-10-17: the gel polish cards must show
 * the bottle tops first). A variant job sets this on the photos it makes; this sets it the moment staff mark the part,
 * on photos already made, so a product whose colours were made before the mark need not be made again.
 */
@Service
public class PhotoFocusService {

    private final GeminiImageClient gemini;
    private final ProductImageRepository images;

    public PhotoFocusService(GeminiImageClient gemini, ProductImageRepository images) {
        this.gemini = gemini;
        this.images = images;
    }

    public record Applied(String focus, int photos) {}

    /**
     * @param maskUrl      the mark (white where the part that changes is)
     * @param photoUrl     the photo it was marked on
     * @param colors       a group's own mark: only these colours' photos. Null or empty: the general mark, for the
     *                     photo it was marked on and every AI-made photo of the product...
     * @param exceptColors ...except these colours' (groups with a mark of their own)
     */
    @Transactional
    public Applied apply(UUID productId, String maskUrl, String photoUrl, List<String> colors, List<String> exceptColors) {
        if (productId == null || maskUrl == null || maskUrl.isBlank()) {
            throw new IllegalArgumentException("Say which product and which marked part.");
        }
        String focus = MaskComposer.focusOf(gemini.download(maskUrl));
        if (focus == null) {
            return new Applied(null, 0);
        }
        Set<String> only = keys(colors);
        Set<String> except = keys(exceptColors);
        List<ProductImage> changed = images.findByProductIdInOrderBySortOrderAsc(List.of(productId)).stream()
                .filter(i -> {
                    String colour = i.getVariant() == null ? null : key(i.getVariant().getOptionColor());
                    if (!only.isEmpty()) return colour != null && only.contains(colour);
                    if (colour != null && except.contains(colour)) return false;
                    return (photoUrl != null && photoUrl.equals(i.getUrl()))
                            || i.getSource() == ProductImage.ImageSource.AI_CLEANED;
                })
                .filter(i -> !focus.equals(i.getFocus()))
                .toList();
        changed.forEach(i -> i.setFocus(focus));
        images.saveAll(changed);
        return new Applied(focus, changed.size());
    }

    private static Set<String> keys(List<String> colours) {
        return colours == null ? Set.of() : colours.stream().map(PhotoFocusService::key)
                .filter(k -> k != null).collect(Collectors.toSet());
    }

    /** Names compared as the variant writer does: tidied ("baby pink" = "Baby Pink"), any case. */
    private static String key(String colour) {
        if (colour == null || colour.isBlank()) return null;
        String tidy = VariantCombos.tidyColour(colour);
        return (tidy == null ? colour : tidy).trim().toLowerCase(Locale.ROOT);
    }
}
