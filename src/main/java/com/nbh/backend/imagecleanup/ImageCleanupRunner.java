package com.nbh.backend.imagecleanup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Runs one batch in the background: for each option, download its real photo once, ask Gemini for each requested
 * view, stamp the logo, upload, and record — straight into the shop, or as drafts for a person to approve, as the
 * batch says. No self-imposed spending cap — the batch runs until Gemini's own quota stops it, at which point the
 * whole batch halts rather than failing every remaining option the same way.
 */
@Slf4j
@Service
public class ImageCleanupRunner {

    private final ImageCleanupWriter writer;
    private final GeminiImageClient gemini;
    private final ImageCleanupPromptBuilder prompts;
    private final WatermarkService watermark;
    private final CloudinaryUploader cloudinary;
    private final ImageCleanupConfig config;
    private final DescriptionDraftService descriptions;

    public ImageCleanupRunner(ImageCleanupWriter writer, GeminiImageClient gemini, ImageCleanupPromptBuilder prompts,
                              WatermarkService watermark, CloudinaryUploader cloudinary, ImageCleanupConfig config,
                              DescriptionDraftService descriptions) {
        this.writer = writer;
        this.gemini = gemini;
        this.prompts = prompts;
        this.watermark = watermark;
        this.cloudinary = cloudinary;
        this.config = config;
        this.descriptions = descriptions;
    }

    /**
     * Everything a run needs to know, fixed when it starts. {@code sourceImageId} is set only for a run on one chosen
     * option. {@code skipCleaned}: a bulk run leaves an option alone once it has an AI photo; a run staff start on a
     * chosen option does not.
     */
    public record Plan(UUID batchId, List<UUID> variantIds, UUID sourceImageId, List<PhotoView> views, String note,
                       ImageCleanupBatch.PublishMode mode, boolean skipCleaned, boolean describe) {}

    @Async
    public void run(Plan plan) {
        log.info("Photo batch {} ({}): {} option(s), {} view(s) each", plan.batchId(), plan.mode(),
                plan.variantIds().size(), plan.views().size());
        boolean anyFailed = false;
        // One description per product per run, however many options it has.
        Set<UUID> described = new HashSet<>();
        try {
            for (UUID variantId : plan.variantIds()) {
                OptionOutcome outcome = cleanOne(plan, variantId, described);
                anyFailed |= outcome.failed();
                if (outcome.quotaExceeded()) {
                    writer.finalizeBatch(plan.batchId(), ImageCleanupBatch.Status.STOPPED_GEMINI_QUOTA,
                            "Gemini's quota or rate limit was reached — the rest of the batch was not attempted.");
                    return;
                }
            }
            writer.finalizeBatch(plan.batchId(), anyFailed
                    ? ImageCleanupBatch.Status.COMPLETED_WITH_ERRORS : ImageCleanupBatch.Status.COMPLETED, null);
        } catch (RuntimeException e) {
            log.error("Photo batch {} crashed", plan.batchId(), e);
            writer.finalizeBatch(plan.batchId(), ImageCleanupBatch.Status.FAILED, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * A run that makes no pictures: for each product, one description draft from its own photo when it has one (else
     * from its name), for staff to approve. Stops the whole run on a Gemini quota error like a photo run does.
     */
    @Async
    public void runDescriptions(UUID batchId, List<UUID> productIds, String note) {
        log.info("Description batch {}: {} product(s)", batchId, productIds.size());
        boolean anyFailed = false;
        try {
            for (UUID productId : productIds) {
                try {
                    boolean drafted = descriptions.draftFor(batchId, productId, descriptions.photoFor(productId), note);
                    writer.recordDescriptionOnly(batchId, drafted);
                } catch (GeminiImageClient.GeminiException e) {
                    anyFailed = true;
                    writer.recordFailure(batchId, "A description wasn't written: " + e.getMessage());
                    if (e.isQuotaExceeded()) {
                        writer.finalizeBatch(batchId, ImageCleanupBatch.Status.STOPPED_GEMINI_QUOTA,
                                "Gemini's quota or rate limit was reached — the rest of the products were not attempted.");
                        return;
                    }
                } catch (RuntimeException e) {
                    anyFailed = true;
                    log.error("Description failed for product {}", productId, e);
                    writer.recordFailure(batchId, "A description wasn't written: " + e.getMessage());
                }
            }
            writer.finalizeBatch(batchId, anyFailed
                    ? ImageCleanupBatch.Status.COMPLETED_WITH_ERRORS : ImageCleanupBatch.Status.COMPLETED, null);
        } catch (RuntimeException e) {
            log.error("Description batch {} crashed", batchId, e);
            writer.finalizeBatch(batchId, ImageCleanupBatch.Status.FAILED, "Unexpected error: " + e.getMessage());
        }
    }

    private record OptionOutcome(boolean failed, boolean quotaExceeded) {}

    /**
     * Drafts the product's text from the same real photo. A failure here is reported on the batch but never stops the
     * photos: the staff note is NOT passed on, because it describes how the photo should look, not the product.
     */
    private void draftDescription(Plan plan, ImageCleanupWriter.Target target, GeminiImageClient.Image reference) {
        try {
            if (descriptions.draftFor(plan.batchId(), target.productId(), reference, null)) {
                writer.recordDescription(plan.batchId());
            }
        } catch (GeminiImageClient.GeminiException e) {
            writer.recordFailure(plan.batchId(), target.displayName() + " — description not written: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Description failed for product {}", target.productId(), e);
            writer.recordFailure(plan.batchId(), target.displayName() + " — description not written: " + e.getMessage());
        }
    }

    private OptionOutcome cleanOne(Plan plan, UUID variantId, Set<UUID> described) {
        var target = writer.targetFor(variantId, plan.sourceImageId(), plan.skipCleaned()).orElse(null);
        if (target == null) {
            writer.recordFailure(plan.batchId(), "A product no longer has a photo to work from (removed, or already cleaned).");
            return new OptionOutcome(true, false);
        }
        List<ImageCleanupWriter.Generated> generated = new ArrayList<>();
        String reason = null;
        boolean quota = false;
        try {
            GeminiImageClient.Image reference = gemini.download(target.referenceUrl());
            if (plan.describe() && described.add(target.productId())) {
                draftDescription(plan, target, reference);
            }
            for (PhotoView view : plan.views()) {
                String prompt = prompts.build(target.displayName(), view, plan.note(), config.getBackgroundHex());
                GeminiImageClient.Image edited = gemini.edit(reference, prompt);
                byte[] stamped = watermark.stamp(edited.bytes());
                // Named after the OPTION and the view: two options of one product made in the same batch must not
                // overwrite each other, nor two views of the same option.
                String publicId = variantId + "-" + view.name().toLowerCase(Locale.ROOT) + "-"
                        + plan.batchId().toString().substring(0, 8);
                CloudinaryUploader.Uploaded up = cloudinary.upload(stamped, "products", publicId);
                generated.add(new ImageCleanupWriter.Generated(up.url(), up.publicId(), prompt, view));
            }
        } catch (GeminiImageClient.GeminiException e) {
            reason = e.getMessage();
            quota = e.isQuotaExceeded();
        } catch (RuntimeException e) {
            log.error("Photo run failed for option {}", variantId, e);
            reason = "Unexpected error: " + e.getMessage();
        }

        if (generated.isEmpty()) {
            writer.recordFailure(plan.batchId(), target.displayName() + " — " + (reason != null ? reason : "no images produced"));
            return new OptionOutcome(true, quota);
        }
        if (plan.mode() == ImageCleanupBatch.PublishMode.REVIEW) {
            writer.recordDrafts(plan.batchId(), target, generated, plan.note());
        } else if (!writer.recordSuccess(plan.batchId(), target, generated)) {
            generated.forEach(g -> cloudinary.delete(g.publicId()));
            writer.recordFailure(plan.batchId(), target.displayName()
                    + " — its photo changed while cleaning, so the result was discarded.");
            return new OptionOutcome(true, false);
        }
        if (reason != null) {
            writer.recordFailure(plan.batchId(), target.displayName() + " — only " + generated.size() + " of "
                    + plan.views().size() + " images were produced: " + reason);
            return new OptionOutcome(true, quota);
        }
        return new OptionOutcome(false, false);
    }
}
