package com.nbh.backend.variantgen;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.ImageCleanupBatch;
import com.nbh.backend.imagecleanup.ImageCleanupBatchRepository;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.imagecleanup.ImageCleanupService;
import com.nbh.backend.imagecleanup.ImageCleanupService.BatchDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Starts variant generation and answers the screens about it. Every run is one background job on an
 * {@code image_cleanup_batch} row (one run at a time, like the photo studio, because they share Gemini's quota).
 */
@Service
public class VariantGenerationService {

    public record Request(UUID productId, UUID baseVariantId, List<String> colors, List<String> sizes, String prompt,
                          BigDecimal price, String baseOption, boolean approveFirst) {}

    public record VariantRow(UUID variantId, UUID productId, String productName, String variantName, String optionLabel,
                             String color, String size, String swatchHex, String sku, boolean active) {}

    public record RunDto(BatchDto batch, List<VariantRow> variants) {}

    private final ImageCleanupBatchRepository batches;
    private final ProductVariantRepository variants;
    private final VariantGenerationWriter writer;
    private final VariantGenerationRunner runner;
    private final ImageCleanupService batchService;
    private final ImageCleanupConfig config;
    private final CloudinaryUploader cloudinary;

    public VariantGenerationService(ImageCleanupBatchRepository batches, ProductVariantRepository variants,
                                    VariantGenerationWriter writer, VariantGenerationRunner runner,
                                    ImageCleanupService batchService, ImageCleanupConfig config,
                                    CloudinaryUploader cloudinary) {
        this.batches = batches;
        this.variants = variants;
        this.writer = writer;
        this.runner = runner;
        this.batchService = batchService;
        this.config = config;
        this.cloudinary = cloudinary;
    }

    public BatchDto start(Request r, UUID staffId) {
        checkReady();
        if (r.price() != null && r.price().signum() < 0) {
            throw new UnprocessableException("The price can't be negative.");
        }
        var source = writer.source(r.productId(), r.baseVariantId())
                .orElseThrow(() -> new NotFoundException("That product or its base variant was not found."));
        String prompt = r.prompt() == null ? null : r.prompt().replaceAll("\\s+", " ").trim();
        if (prompt != null && prompt.length() > 2000) {
            throw new UnprocessableException("The prompt is too long (2000 characters at most).");
        }
        ImageCleanupBatch batch = newBatch(prompt, r.approveFirst(), 0, staffId);
        runner.generate(new VariantGenerationRunner.Job(batch.getId(), r.productId(), source.baseVariantId(), r.colors(),
                r.sizes(), prompt, r.price(), blankToNull(r.baseOption()), r.approveFirst()));
        return batchService.get(batch.getId());
    }

    /** Retries photos for the given generated variants (all that need images when {@code variantIds} is empty). */
    public BatchDto retryPhotos(List<UUID> variantIds, boolean approveFirst, UUID staffId) {
        checkReady();
        List<UUID> ids = variantIds == null || variantIds.isEmpty()
                ? variants.findGeneratedNeedingImages().stream().map(ProductVariant::getId).toList() : variantIds;
        if (ids.isEmpty()) {
            throw new UnprocessableException("No variants need images right now.");
        }
        ImageCleanupBatch batch = newBatch(null, approveFirst, ids.size(), staffId);
        runner.retryPhotos(batch.getId(), approveFirst, ids);
        return batchService.get(batch.getId());
    }

    private void checkReady() {
        if (!config.geminiConfigured()) {
            throw new UnprocessableException("Gemini isn't set up yet — add the GEMINI_API_KEY before generating variants.");
        }
        if (!cloudinary.configured()) {
            throw new UnprocessableException("Cloudinary isn't set up yet — the photos can't be stored.");
        }
    }

    private ImageCleanupBatch newBatch(String note, boolean approveFirst, int requested, UUID staffId) {
        if (batches.existsByStatus(ImageCleanupBatch.Status.IN_PROGRESS)) {
            throw new ConflictException("A run is already in progress — wait for it to finish.");
        }
        ImageCleanupBatch b = new ImageCleanupBatch();
        b.setImagesPerProduct(1);
        b.setViews("CLEAN");
        b.setNote(note == null ? null : (note.length() > 600 ? note.substring(0, 600) : note));
        b.setPublishMode(approveFirst ? ImageCleanupBatch.PublishMode.REVIEW : ImageCleanupBatch.PublishMode.IMMEDIATE);
        b.setRequestedCount(requested);
        b.setTriggeredByUserId(staffId);
        return batches.saveAndFlush(b);
    }

    @Transactional(readOnly = true)
    public RunDto run(UUID batchId) {
        BatchDto batch = batchService.get(batchId);
        return new RunDto(batch, variants.findByGenerationBatchId(batchId).stream().filter(v -> !v.isDeleted())
                .map(VariantGenerationService::row).toList());
    }

    /** Approve-first runs: makes the run's hidden variants visible to shoppers. */
    public int publishRun(UUID batchId) {
        if (!batches.existsById(batchId)) {
            throw new NotFoundException("No such run.");
        }
        return writer.publishRun(batchId);
    }

    @Transactional(readOnly = true)
    public List<VariantRow> needingImages() {
        return variants.findGeneratedNeedingImages().stream().map(VariantGenerationService::row).toList();
    }

    private static VariantRow row(ProductVariant v) {
        return new VariantRow(v.getId(), v.getProduct().getId(), v.getProduct().getName(), v.getVariantName(),
                v.getOptionLabel(), v.getOptionColor(), v.getOptionSize(), v.getSwatchHex(), v.getSku(), v.isActive());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
