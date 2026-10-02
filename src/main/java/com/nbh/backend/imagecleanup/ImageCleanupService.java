package com.nbh.backend.imagecleanup;

import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.UnprocessableException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Starting, listing and undoing AI photo-cleanup batches; the generation itself is {@link ImageCleanupRunner}. */
@Service
public class ImageCleanupService {

    public record BatchDto(UUID id, ImageCleanupBatch.Status status, int imagesPerProduct, int requestedCount,
                           int succeededCount, int failedCount, String lastError, Instant createdAt) {
        static BatchDto from(ImageCleanupBatch b) {
            return new BatchDto(b.getId(), b.getStatus(), b.getImagesPerProduct(), b.getRequestedCount(),
                    b.getSucceededCount(), b.getFailedCount(), b.getLastError(), b.getCreatedAt());
        }
    }

    public record StatusDto(boolean geminiConfigured, boolean cloudinaryConfigured, long candidates,
                            int maxImagesPerProduct, int defaultImagesPerProduct, String backgroundHex) {}

    private final ImageCleanupBatchRepository batches;
    private final ProductImageRepository images;
    private final ImageCleanupWriter writer;
    private final ImageCleanupRunner runner;
    private final CloudinaryUploader cloudinary;
    private final ImageCleanupConfig config;

    public ImageCleanupService(ImageCleanupBatchRepository batches, ProductImageRepository images,
                               ImageCleanupWriter writer, ImageCleanupRunner runner,
                               CloudinaryUploader cloudinary, ImageCleanupConfig config) {
        this.batches = batches;
        this.images = images;
        this.writer = writer;
        this.runner = runner;
        this.cloudinary = cloudinary;
        this.config = config;
    }

    @Transactional(readOnly = true)
    public StatusDto status() {
        return new StatusDto(config.geminiConfigured(), cloudinary.configured(),
                images.countCleanupCandidates(ProductImage.ImageSource.AI_CLEANED),
                config.getMaxImagesPerProduct(), 3, config.getBackgroundHex());
    }

    /** Cleans the next {@code limit} products that have a real photo and haven't been cleaned yet. */
    public BatchDto startBatch(int limit, int imagesPerProduct, UUID staffId) {
        List<UUID> productIds = images.cleanupCandidates(ProductImage.ImageSource.AI_CLEANED, PageRequest.of(0, limit))
                .stream().map(i -> i.getProduct().getId()).distinct().toList();
        if (productIds.isEmpty()) {
            throw new UnprocessableException("No products currently need photo cleanup.");
        }
        return start(productIds, imagesPerProduct, staffId);
    }

    /** Cleans one chosen product (it must have a real, not-yet-cleaned photo). */
    public BatchDto startForProduct(UUID productId, int imagesPerProduct, UUID staffId) {
        if (writer.targetFor(productId).isEmpty()) {
            throw new UnprocessableException(
                    "This product has no un-cleaned photo to work from. Upload a real photo first, or undo its earlier cleanup.");
        }
        return start(List.of(productId), imagesPerProduct, staffId);
    }

    private BatchDto start(List<UUID> productIds, int imagesPerProduct, UUID staffId) {
        if (!config.geminiConfigured()) {
            throw new UnprocessableException("Gemini isn't set up yet — add the GEMINI_API_KEY before cleaning photos.");
        }
        if (!cloudinary.configured()) {
            throw new UnprocessableException("Cloudinary isn't set up yet — cleaned photos can't be stored.");
        }
        if (batches.existsByStatus(ImageCleanupBatch.Status.IN_PROGRESS)) {
            throw new ConflictException("A cleanup batch is already running — wait for it to finish.");
        }
        int perProduct = Math.max(1, Math.min(imagesPerProduct, config.getMaxImagesPerProduct()));
        ImageCleanupBatch batch = new ImageCleanupBatch();
        batch.setImagesPerProduct(perProduct);
        batch.setRequestedCount(productIds.size());
        batch.setTriggeredByUserId(staffId);
        batch = batches.saveAndFlush(batch);
        runner.run(batch.getId(), productIds, perProduct);
        return BatchDto.from(batch);
    }

    @Transactional(readOnly = true)
    public List<BatchDto> recent() {
        return batches.recent(PageRequest.of(0, 50)).stream().map(BatchDto::from).toList();
    }

    @Transactional(readOnly = true)
    public BatchDto get(UUID id) {
        return batches.findById(id).map(BatchDto::from)
                .orElseThrow(() -> new com.nbh.backend.common.exception.NotFoundException("No cleanup batch: " + id));
    }

    /** Undo a whole batch: generated images removed, each original photo is the primary image again. */
    public BatchDto undo(UUID id) {
        ImageCleanupBatch batch = batches.findById(id)
                .orElseThrow(() -> new com.nbh.backend.common.exception.NotFoundException("No cleanup batch: " + id));
        if (batch.getStatus() == ImageCleanupBatch.Status.IN_PROGRESS) {
            throw new ConflictException("This batch is still running.");
        }
        if (batch.getStatus() == ImageCleanupBatch.Status.DELETED) {
            throw new ConflictException("This batch was already undone.");
        }
        writer.undo(id).forEach(cloudinary::delete);
        return get(id);
    }
}
