package com.nbh.backend.imagecleanup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs one cleanup batch in the background: for each product, download the real photo once, ask Gemini for
 * {@code imagesPerProduct} different views, stamp the logo, upload, and record. No self-imposed spending cap —
 * the batch runs until Gemini's own quota stops it, at which point the whole batch halts rather than failing
 * every remaining product the same way.
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

    public ImageCleanupRunner(ImageCleanupWriter writer, GeminiImageClient gemini, ImageCleanupPromptBuilder prompts,
                              WatermarkService watermark, CloudinaryUploader cloudinary, ImageCleanupConfig config) {
        this.writer = writer;
        this.gemini = gemini;
        this.prompts = prompts;
        this.watermark = watermark;
        this.cloudinary = cloudinary;
        this.config = config;
    }

    @Async
    public void run(UUID batchId, List<UUID> productIds, int imagesPerProduct) {
        log.info("Image cleanup batch {}: {} product(s), {} image(s) each", batchId, productIds.size(), imagesPerProduct);
        boolean anyFailed = false;
        try {
            for (UUID productId : productIds) {
                ProductOutcome outcome = cleanOne(batchId, productId, imagesPerProduct);
                anyFailed |= outcome.failed();
                if (outcome.quotaExceeded()) {
                    writer.finalizeBatch(batchId, ImageCleanupBatch.Status.STOPPED_GEMINI_QUOTA,
                            "Gemini's quota or rate limit was reached — the rest of the batch was not attempted.");
                    return;
                }
            }
            writer.finalizeBatch(batchId, anyFailed
                    ? ImageCleanupBatch.Status.COMPLETED_WITH_ERRORS : ImageCleanupBatch.Status.COMPLETED, null);
        } catch (RuntimeException e) {
            log.error("Image cleanup batch {} crashed", batchId, e);
            writer.finalizeBatch(batchId, ImageCleanupBatch.Status.FAILED, "Unexpected error: " + e.getMessage());
        }
    }

    private record ProductOutcome(boolean failed, boolean quotaExceeded) {}

    private ProductOutcome cleanOne(UUID batchId, UUID productId, int imagesPerProduct) {
        var target = writer.targetFor(productId).orElse(null);
        if (target == null) {
            writer.recordFailure(batchId, "A product no longer has a photo to clean (deleted, or already cleaned).");
            return new ProductOutcome(true, false);
        }
        List<ImageCleanupWriter.Generated> generated = new ArrayList<>();
        String reason = null;
        boolean quota = false;
        try {
            GeminiImageClient.Image reference = gemini.download(target.referenceUrl());
            for (int i = 0; i < imagesPerProduct; i++) {
                String prompt = prompts.build(target.productName(), i, config.getBackgroundHex());
                GeminiImageClient.Image edited = gemini.edit(reference, prompt);
                byte[] stamped = watermark.stamp(edited.bytes());
                String publicId = productId + "-clean-" + (i + 1) + "-" + batchId.toString().substring(0, 8);
                CloudinaryUploader.Uploaded up = cloudinary.upload(stamped, "products", publicId);
                generated.add(new ImageCleanupWriter.Generated(up.url(), up.publicId(), prompt));
            }
        } catch (GeminiImageClient.GeminiException e) {
            reason = e.getMessage();
            quota = e.isQuotaExceeded();
        } catch (RuntimeException e) {
            log.error("Cleanup failed for product {}", productId, e);
            reason = "Unexpected error: " + e.getMessage();
        }

        if (generated.isEmpty()) {
            writer.recordFailure(batchId, target.productName() + " — " + (reason != null ? reason : "no images produced"));
            return new ProductOutcome(true, quota);
        }
        if (!writer.recordSuccess(batchId, target, generated)) {
            generated.forEach(g -> cloudinary.delete(g.publicId()));
            writer.recordFailure(batchId, target.productName()
                    + " — its photo changed while cleaning, so the result was discarded.");
            return new ProductOutcome(true, false);
        }
        if (reason != null) {
            writer.recordFailure(batchId, target.productName() + " — only " + generated.size() + " of "
                    + imagesPerProduct + " images were produced: " + reason);
            return new ProductOutcome(true, quota);
        }
        return new ProductOutcome(false, false);
    }
}
