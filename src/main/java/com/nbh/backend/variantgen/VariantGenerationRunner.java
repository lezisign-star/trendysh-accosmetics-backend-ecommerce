package com.nbh.backend.variantgen;

import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.ImageCleanupBatch;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.imagecleanup.ImageCleanupWriter;
import com.nbh.backend.imagecleanup.PhotoView;
import com.nbh.backend.imagecleanup.WatermarkService;
import com.nbh.backend.variantgen.VariantCombos.Combo;
import com.nbh.backend.variantgen.VariantGenerationWriter.NewVariant;
import com.nbh.backend.variantgen.VariantGenerationWriter.Planned;
import com.nbh.backend.variantgen.VariantGenerationWriter.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one variant-generation job in the background: plan the options (Gemini text), create the variants, then make one
 * photo per variant from the base photo. The job rides on an ordinary {@code image_cleanup_batch} row so progress,
 * drafts, the review screens and "undo" of the photo studio work on it unchanged. A photo that fails never stops the
 * variants: the variant stays (visible unless the run is approve-first) and shows in the "needs images" list for a retry.
 * A Gemini quota error stops the remaining photos only.
 */
@Slf4j
@Service
public class VariantGenerationRunner {

    /** Everything a run needs, fixed when it starts. */
    public record Job(UUID batchId, UUID productId, UUID baseVariantId, List<String> colors, List<String> sizes,
                      String prompt, BigDecimal price, String baseOption, boolean approveFirst) {}

    private final VariantGenerationWriter writer;
    private final ImageCleanupWriter batchWriter;
    private final VariantPlanner planner;
    private final VariantPhotoPromptBuilder prompts;
    private final GeminiImageClient gemini;
    private final WatermarkService watermark;
    private final CloudinaryUploader cloudinary;
    private final ImageCleanupConfig config;

    public VariantGenerationRunner(VariantGenerationWriter writer, ImageCleanupWriter batchWriter, VariantPlanner planner,
                                   VariantPhotoPromptBuilder prompts, GeminiImageClient gemini, WatermarkService watermark,
                                   CloudinaryUploader cloudinary, ImageCleanupConfig config) {
        this.writer = writer;
        this.batchWriter = batchWriter;
        this.planner = planner;
        this.prompts = prompts;
        this.gemini = gemini;
        this.watermark = watermark;
        this.cloudinary = cloudinary;
        this.config = config;
    }

    @Async
    public void generate(Job job) {
        UUID batchId = job.batchId();
        try {
            var text = writer.baseText(job.productId(), job.baseVariantId());
            List<String> colors = VariantCombos.clean(job.colors());
            List<String> sizes = VariantCombos.clean(job.sizes());
            if (colors.isEmpty() && sizes.isEmpty()) {
                VariantPlanner.Axes axes = planner.extractAxes(text.productName(), text.baseOption(), text.description(),
                        job.prompt());
                colors = axes.colors();
                sizes = axes.sizes();
            }
            List<Combo> combos = withoutExisting(job.productId(), VariantCombos.build(colors, sizes));
            if (combos.isEmpty()) {
                batchWriter.finalizeBatch(batchId, ImageCleanupBatch.Status.FAILED,
                        "No new colours or sizes to make: none were given or found in the prompt, or the product already has them.");
                return;
            }
            if (combos.size() > VariantCombos.MAX_VARIANTS) {
                batchWriter.finalizeBatch(batchId, ImageCleanupBatch.Status.FAILED, "That would make " + combos.size()
                        + " variants; the most in one run is " + VariantCombos.MAX_VARIANTS + ". Give fewer colours or sizes.");
                return;
            }
            List<VariantPlanner.Details> details;
            try {
                details = planner.details(text.productName(), text.description(), job.prompt(), combos);
            } catch (GeminiImageClient.GeminiException e) {
                if (e.isQuotaExceeded()) {
                    batchWriter.finalizeBatch(batchId, ImageCleanupBatch.Status.STOPPED_GEMINI_QUOTA,
                            "Gemini's quota or rate limit was reached before the variants were planned.");
                    return;
                }
                log.warn("Variant details not written for batch {}: {}", batchId, e.getMessage());
                details = combos.stream().map(c -> new VariantPlanner.Details(null, null)).toList();
            }
            List<Planned> planned = new ArrayList<>();
            for (int i = 0; i < combos.size(); i++) {
                planned.add(new Planned(combos.get(i), details.get(i).swatchHex(), details.get(i).description()));
            }
            List<NewVariant> created = writer.createVariants(batchId, job.productId(), job.baseVariantId(), planned,
                    job.price(), !job.approveFirst(), job.baseOption());

            Source source = writer.source(job.productId(), job.baseVariantId()).orElse(null);
            if (source == null || source.referenceUrl() == null) {
                batchWriter.recordFailure(batchId, "The base variant has no photo to work from, so the new variants have "
                        + "none yet. Add a photo to the base, then retry from \"Needs images\".");
                batchWriter.finalizeBatch(batchId, ImageCleanupBatch.Status.COMPLETED_WITH_ERRORS, null);
                return;
            }
            boolean review = job.approveFirst();
            Outcome outcome = photos(batchId, review, source, gemini.download(source.referenceUrl()),
                    created, job.prompt());
            finish(batchId, outcome);
        } catch (GeminiImageClient.GeminiException e) {
            batchWriter.finalizeBatch(batchId, e.isQuotaExceeded() ? ImageCleanupBatch.Status.STOPPED_GEMINI_QUOTA
                    : ImageCleanupBatch.Status.FAILED, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Variant generation {} crashed", batchId, e);
            batchWriter.finalizeBatch(batchId, ImageCleanupBatch.Status.FAILED, "Unexpected error: " + e.getMessage());
        }
    }

    /** Retries photos for generated variants that have none, each from its own base's photo. */
    @Async
    public void retryPhotos(UUID batchId, boolean review, List<UUID> variantIds) {
        try {
            Map<String, GeminiImageClient.Image> downloaded = new HashMap<>();
            Outcome total = new Outcome(false, false);
            for (UUID id : variantIds) {
                var target = writer.retryTarget(id).orElse(null);
                if (target == null || target.source().referenceUrl() == null) {
                    batchWriter.recordFailure(batchId, "A variant can't be retried: it was removed, or its base has no photo.");
                    total = new Outcome(true, total.quota());
                    continue;
                }
                GeminiImageClient.Image ref;
                try {
                    ref = downloaded.computeIfAbsent(target.source().referenceUrl(), gemini::download);
                } catch (GeminiImageClient.GeminiException e) {
                    batchWriter.recordFailure(batchId, target.variant().displayName() + " — " + e.getMessage());
                    total = new Outcome(true, total.quota());
                    continue;
                }
                Outcome o = photos(batchId, review, target.source(), ref, List.of(target.variant()), target.note());
                total = new Outcome(total.failed() || o.failed(), o.quota());
                if (o.quota()) {
                    break;
                }
            }
            finish(batchId, total);
        } catch (RuntimeException e) {
            log.error("Variant photo retry {} crashed", batchId, e);
            batchWriter.finalizeBatch(batchId, ImageCleanupBatch.Status.FAILED, "Unexpected error: " + e.getMessage());
        }
    }

    private record Outcome(boolean failed, boolean quota) {}

    private void finish(UUID batchId, Outcome o) {
        if (o.quota()) {
            batchWriter.finalizeBatch(batchId, ImageCleanupBatch.Status.STOPPED_GEMINI_QUOTA,
                    "Gemini's quota or rate limit was reached — the remaining photos were not made. Retry them from \"Needs images\".");
        } else {
            batchWriter.finalizeBatch(batchId, o.failed() ? ImageCleanupBatch.Status.COMPLETED_WITH_ERRORS
                    : ImageCleanupBatch.Status.COMPLETED, null);
        }
    }

    /** One photo per variant. Never throws; a failed photo is reported on the batch and the loop carries on. */
    private Outcome photos(UUID batchId, boolean review, Source source, GeminiImageClient.Image reference,
                           List<NewVariant> variants, String labelNote) {
        boolean failed = false;
        for (NewVariant v : variants) {
            String publicId = v.variantId() + "-variant-" + batchId.toString().substring(0, 8);
            String prompt = prompts.build(source.productName(), v.color(), v.swatchHex(), v.size(), labelNote,
                    config.getBackgroundHex());
            try {
                GeminiImageClient.Image edited = gemini.edit(reference, prompt);
                CloudinaryUploader.Uploaded up = cloudinary.upload(watermark.stamp(edited.bytes()), "products", publicId);
                if (review) {
                    batchWriter.recordDrafts(batchId, new ImageCleanupWriter.Target(source.productId(), v.variantId(),
                            v.displayName(), source.referenceImageId(), source.referenceUrl(), true),
                            List.of(new ImageCleanupWriter.Generated(up.url(), up.publicId(), prompt, PhotoView.CLEAN)),
                            labelNote == null ? null : trim(labelNote));
                } else {
                    writer.recordPhoto(batchId, source, v, up.url(), up.publicId(), prompt);
                }
            } catch (GeminiImageClient.GeminiException e) {
                failed = true;
                batchWriter.recordFailure(batchId, v.displayName() + " — " + e.getMessage());
                if (e.isQuotaExceeded()) {
                    return new Outcome(true, true);
                }
            } catch (RuntimeException e) {
                failed = true;
                log.error("Variant photo failed for {}", v.variantId(), e);
                batchWriter.recordFailure(batchId, v.displayName() + " — " + e.getMessage());
            }
        }
        return new Outcome(failed, false);
    }

    private static String trim(String s) {
        return s.length() <= 600 ? s : s.substring(0, 600);
    }

    private List<Combo> withoutExisting(UUID productId, List<Combo> combos) {
        List<String> existing = writer.existingLabels(productId);
        return combos.stream().filter(c -> !existing.contains(c.label().toLowerCase(Locale.ROOT))).toList();
    }
}
