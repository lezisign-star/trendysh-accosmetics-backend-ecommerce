package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds the variant photos with a pale patch on them (owner, 2026-10-17): photos made by the AI before the check in
 * {@link MaskComposer} existed, where it moved the product and its own backdrop showed through the part staff marked.
 * Each variant's main AI-made photo is compared with the photo the variants were made from; staff then redo the ones
 * found (Redo photos ticks them).
 */
@Service
public class PatchCheckService {

    private static final Logger log = LoggerFactory.getLogger(PatchCheckService.class);

    /** More of the photo than this showing the backdrop where the product's photo has none counts as a patch. */
    static final double PATCH_SHARE = 0.001;

    private final VariantJobWriter writer;
    private final ProductImageRepository images;
    private final GeminiImageClient gemini;
    private final ImageCleanupConfig config;

    public PatchCheckService(VariantJobWriter writer, ProductImageRepository images, GeminiImageClient gemini,
                             ImageCleanupConfig config) {
        this.writer = writer;
        this.images = images;
        this.gemini = gemini;
        this.config = config;
    }

    public record Found(List<UUID> variantIds, int checked) {}

    public Found check(UUID productId, UUID baseVariantId) {
        var base = writer.base(productId, baseVariantId)
                .orElseThrow(() -> new NotFoundException("The product has no variant to start from."));
        VariantJobWriter.Source source = writer.basePhoto(productId, base.getId());
        if (source == null) throw new UnprocessableException("The product has no photo to compare with.");
        GeminiImageClient.Image start = gemini.download(small(source.url()));

        // Each variant's main AI-made photo (the one its card shows).
        Map<UUID, ProductImage> made = new LinkedHashMap<>();
        for (ProductImage i : images.findByProductIdInOrderBySortOrderAsc(List.of(productId))) {
            if (i.getVariant() == null || i.getSource() != ProductImage.ImageSource.AI_CLEANED) continue;
            UUID v = i.getVariant().getId();
            if (v.equals(base.getId())) continue;
            ProductImage held = made.get(v);
            if (held == null || (i.isPrimary() && !held.isPrimary())) made.put(v, i);
        }
        List<UUID> found = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            Map<UUID, Future<Double>> shares = new LinkedHashMap<>();
            for (var e : made.entrySet()) {
                String url = e.getValue().getUrl();
                shares.put(e.getKey(), pool.submit(() ->
                        MaskComposer.patchShare(start, gemini.download(small(url)), config.getBackgroundHex())));
            }
            for (var e : shares.entrySet()) {
                try {
                    if (e.getValue().get() > PATCH_SHARE) found.add(e.getKey());
                } catch (Exception ex) {
                    log.info("Patch check: a photo of variant {} could not be read: {}", e.getKey(), ex.getMessage());
                }
            }
        }
        return new Found(found, made.size());
    }

    /** A small copy is enough to see a patch, and much quicker to fetch. */
    static String small(String url) {
        return url != null && url.contains("res.cloudinary.com") && url.contains("/image/upload/")
                ? url.replace("/image/upload/", "/image/upload/w_480,c_limit/") : url;
    }
}
