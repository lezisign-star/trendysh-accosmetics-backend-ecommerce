package com.nbh.backend.imagecleanup;

import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.ImageAssetCleaner;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Starting, listing and undoing AI photo batches; the generation itself is {@link ImageCleanupRunner}.
 *
 * <p>The unit of work is one option (a variant): a photo belongs to exactly one option, so the photos made from it do
 * too. Every run says up front whether its photos go straight into the shop or wait as drafts for staff to approve
 * (owner, 2026-10-07; waiting is the default), which views to make, and an optional note in the staff member's words.
 */
@Service
public class ImageCleanupService {

    public record ViewDto(String key, String label, String hint, boolean defaultOn) {}

    public record BatchDto(UUID id, ImageCleanupBatch.Status status, ImageCleanupBatch.PublishMode publishMode,
                           int imagesPerProduct, List<String> views, String note, int requestedCount,
                           int succeededCount, int failedCount, long pendingDrafts, boolean writeDescriptions,
                           int descriptionsDrafted, long pendingDescriptions, String lastError, Instant createdAt) {
        static BatchDto from(ImageCleanupBatch b, long pendingDrafts, long pendingDescriptions) {
            List<String> views = b.getViews() == null || b.getViews().isBlank()
                    ? List.of() : Arrays.asList(b.getViews().split(","));
            return new BatchDto(b.getId(), b.getStatus(), b.getPublishMode(), b.getImagesPerProduct(), views, b.getNote(),
                    b.getRequestedCount(), b.getSucceededCount(), b.getFailedCount(), pendingDrafts, b.isWriteDescriptions(),
                    b.getDescriptionsDrafted(), pendingDescriptions, b.getLastError(), b.getCreatedAt());
        }
    }

    public record StatusDto(boolean geminiConfigured, boolean cloudinaryConfigured, long candidates,
                            int maxImagesPerProduct, int defaultImagesPerProduct, String backgroundHex,
                            List<ViewDto> views, int maxNoteLength, int draftDays, long pendingDrafts,
                            long pendingDescriptions, DescriptionLimits descriptionLimits) {}

    /** The character limits descriptions are written and approved to, shown beside each field. */
    public record DescriptionLimits(int shortDescription, int description, int highlightsCount, int highlight,
                                    int ingredients, int howToUse, int seoTitle, int seoDescription) {}

    /**
     * What a run was asked for. {@code views} are {@link PhotoView} keys; when none are given the first
     * {@code imagesPerProduct} views are used (3 if that is missing too). {@code publishNow} false (the default) holds the
     * photos as drafts.
     */
    public record RunOptions(List<String> views, Integer imagesPerProduct, String note, boolean publishNow,
                             boolean describe) {}

    private static final int DEFAULT_VIEW_COUNT = 3;

    private final ImageCleanupBatchRepository batches;
    private final ProductImageRepository images;
    private final ProductVariantRepository variants;
    private final ImageDraftRepository drafts;
    private final ImageCleanupWriter writer;
    private final ImageCleanupRunner runner;
    private final CloudinaryUploader cloudinary;
    private final ImageCleanupConfig config;
    private final ImageDraftService draftService;
    private final ImageAssetCleaner assetCleaner;
    private final DescriptionDraftService descriptionService;
    private final ProductDescriptionDraftRepository descriptionDrafts;

    public ImageCleanupService(ImageCleanupBatchRepository batches, ProductImageRepository images,
                               ProductVariantRepository variants, ImageDraftRepository drafts,
                               ImageCleanupWriter writer, ImageCleanupRunner runner, CloudinaryUploader cloudinary,
                               ImageCleanupConfig config, ImageDraftService draftService,
                               ImageAssetCleaner assetCleaner, DescriptionDraftService descriptionService,
                               ProductDescriptionDraftRepository descriptionDrafts) {
        this.assetCleaner = assetCleaner;
        this.descriptionService = descriptionService;
        this.descriptionDrafts = descriptionDrafts;
        this.batches = batches;
        this.images = images;
        this.variants = variants;
        this.drafts = drafts;
        this.writer = writer;
        this.runner = runner;
        this.cloudinary = cloudinary;
        this.config = config;
        this.draftService = draftService;
    }

    @Transactional(readOnly = true)
    public StatusDto status() {
        List<PhotoView> defaults = PhotoView.firstOf(DEFAULT_VIEW_COUNT);
        List<ViewDto> views = Arrays.stream(PhotoView.values())
                .map(v -> new ViewDto(v.name(), v.label(), v.hint(), defaults.contains(v))).toList();
        return new StatusDto(config.geminiConfigured(), cloudinary.configured(),
                images.countCleanupCandidates(ProductImage.ImageSource.AI_CLEANED),
                config.getMaxImagesPerProduct(), DEFAULT_VIEW_COUNT, config.getBackgroundHex(), views,
                ImageCleanupPromptBuilder.MAX_NOTE_LENGTH, draftService.draftDays(), draftService.pendingCount(),
                descriptionService.pendingCount(),
                new DescriptionLimits(DescriptionCaps.SHORT_DESCRIPTION, DescriptionCaps.DESCRIPTION,
                        DescriptionCaps.HIGHLIGHTS_COUNT, DescriptionCaps.HIGHLIGHT, DescriptionCaps.INGREDIENTS,
                        DescriptionCaps.HOW_TO_USE, DescriptionCaps.SEO_TITLE, DescriptionCaps.SEO_DESCRIPTION));
    }

    /** Makes photos for the next {@code limit} options that have a real photo and haven't had any made yet. */
    public BatchDto startBatch(int limit, RunOptions options, UUID staffId) {
        List<UUID> variantIds = images.cleanupCandidates(ProductImage.ImageSource.AI_CLEANED, PageRequest.of(0, limit))
                .stream().map(i -> i.getVariant().getId()).distinct().toList();
        if (variantIds.isEmpty()) {
            throw new UnprocessableException("No products currently need photo cleanup.");
        }
        return start(variantIds, null, true, options, staffId);
    }

    /** Makes photos for one chosen product: each of its options that has a real photo and none made yet. */
    public BatchDto startForProduct(UUID productId, RunOptions options, UUID staffId) {
        List<UUID> variantIds = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId).stream()
                .map(ProductVariant::getId)
                .filter(id -> writer.targetFor(id, null, true).isPresent())
                .toList();
        if (variantIds.isEmpty()) {
            throw new UnprocessableException(
                    "This product has no un-cleaned photo to work from. Upload a real photo to one of its options first, or undo its earlier cleanup.");
        }
        return start(variantIds, null, true, options, staffId);
    }

    /**
     * Makes photos for one option, from one of its real photos (staff choose which, else its main one). Unlike the bulk
     * runs this works even when the option already has AI photos: staff may want more views, or to try again.
     */
    public BatchDto startForOption(UUID variantId, UUID sourceImageId, RunOptions options, UUID staffId) {
        if (writer.targetFor(variantId, sourceImageId, false).isEmpty()) {
            throw new UnprocessableException(sourceImageId != null
                    ? "That photo can't be the starting point: it was removed, it belongs to another option, or the AI made it."
                    : "This option has no real photo to work from. Upload a real photo to it first.");
        }
        return start(List.of(variantId), sourceImageId, false, options, staffId);
    }

    /**
     * Makes another photo like a waiting draft (same option, same starting photo, same view) with a new note. The
     * draft being redone stays until staff reject it, so they can compare the two. A null note keeps the old one; an
     * empty one means "no note".
     */
    public BatchDto redo(UUID draftId, String note, UUID staffId) {
        ImageDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new NotFoundException("That photo is no longer waiting for review."));
        PhotoView view = PhotoView.parse(draft.getViewKey()).orElse(PhotoView.CLEAN);
        String effectiveNote = note == null ? draft.getNote() : note;
        return startForOption(draft.getVariantId(), draft.getReferenceImageId(),
                new RunOptions(List.of(view.name()), null, effectiveNote, false, false), staffId);
    }

    private BatchDto start(List<UUID> variantIds, UUID sourceImageId, boolean skipCleaned, RunOptions options, UUID staffId) {
        if (!config.geminiConfigured()) {
            throw new UnprocessableException("Gemini isn't set up yet — add the GEMINI_API_KEY before making photos.");
        }
        if (!cloudinary.configured()) {
            throw new UnprocessableException("Cloudinary isn't set up yet — the photos can't be stored.");
        }
        List<PhotoView> views = resolveViews(options);
        String note = ImageCleanupPromptBuilder.cleanNote(options.note());
        if (batches.existsByStatus(ImageCleanupBatch.Status.IN_PROGRESS)) {
            throw new ConflictException("A photo batch is already running — wait for it to finish.");
        }
        ImageCleanupBatch.PublishMode mode = options.publishNow()
                ? ImageCleanupBatch.PublishMode.IMMEDIATE : ImageCleanupBatch.PublishMode.REVIEW;
        ImageCleanupBatch batch = new ImageCleanupBatch();
        batch.setImagesPerProduct(views.size());
        batch.setViews(views.stream().map(Enum::name).collect(Collectors.joining(",")));
        batch.setNote(note);
        batch.setWriteDescriptions(options.describe());
        batch.setPublishMode(mode);
        batch.setRequestedCount(variantIds.size());
        batch.setTriggeredByUserId(staffId);
        batch = batches.saveAndFlush(batch);
        runner.run(new ImageCleanupRunner.Plan(batch.getId(), variantIds, sourceImageId, views, note, mode, skipCleaned,
                options.describe()));
        return BatchDto.from(batch, 0, 0);
    }

    /** The views to make: the ticked ones, else the first few; never more than the limit. */
    private List<PhotoView> resolveViews(RunOptions options) {
        List<PhotoView> views = new ArrayList<>();
        if (options.views() != null) {
            for (String key : options.views()) {
                PhotoView view = PhotoView.parse(key)
                        .orElseThrow(() -> new UnprocessableException("Unknown photo view: " + key));
                if (!views.contains(view)) {
                    views.add(view);
                }
            }
        }
        if (views.isEmpty()) {
            views = new ArrayList<>(PhotoView.firstOf(options.imagesPerProduct() != null
                    ? options.imagesPerProduct() : DEFAULT_VIEW_COUNT));
        }
        if (views.size() > config.getMaxImagesPerProduct()) {
            throw new UnprocessableException("Choose at most " + config.getMaxImagesPerProduct() + " views.");
        }
        return views;
    }

    @Transactional(readOnly = true)
    public List<BatchDto> recent() {
        Map<UUID, Long> pending = drafts.countByBatch().stream()
                .collect(Collectors.toMap(ImageDraftRepository.BatchDraftCount::getBatchId,
                        ImageDraftRepository.BatchDraftCount::getDrafts));
        Map<UUID, Long> pendingText = descriptionDrafts.countByBatch().stream()
                .collect(Collectors.toMap(ProductDescriptionDraftRepository.BatchCount::getBatchId,
                        ProductDescriptionDraftRepository.BatchCount::getDrafts));
        return batches.recent(PageRequest.of(0, 50)).stream()
                .map(b -> BatchDto.from(b, pending.getOrDefault(b.getId(), 0L), pendingText.getOrDefault(b.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public BatchDto get(UUID id) {
        return batches.findById(id).map(b -> BatchDto.from(b, drafts.countByBatchId(b.getId()),
                descriptionDrafts.countByBatchId(b.getId())))
                .orElseThrow(() -> new NotFoundException("No cleanup batch: " + id));
    }

    /**
     * Undo a whole batch: what it published is removed (each original photo is its option's main photo again), the
     * drafts of it still waiting are discarded, and their files are deleted from Cloudinary.
     */
    public BatchDto undo(UUID id) {
        ImageCleanupBatch batch = batches.findById(id)
                .orElseThrow(() -> new NotFoundException("No cleanup batch: " + id));
        if (batch.getStatus() == ImageCleanupBatch.Status.IN_PROGRESS) {
            throw new ConflictException("This batch is still running.");
        }
        if (batch.getStatus() == ImageCleanupBatch.Status.DELETED) {
            throw new ConflictException("This batch was already undone.");
        }
        // Through the asset cleaner, not straight to Cloudinary: a duplicated product's photo may point at the same file.
        writer.undo(id).forEach(r -> assetCleaner.releaseKnown(r.url(), r.publicId()));
        descriptionService.discardBatch(id);
        return get(id);
    }
}
