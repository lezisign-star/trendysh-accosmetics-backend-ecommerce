package com.nbh.backend.imagecleanup;

import com.nbh.backend.catalog.dto.CatalogVariantCardDto;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.PhotoOwnership;
import com.nbh.backend.common.event.DataChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional database half of a run, a separate bean from the {@code @Async} runner so each option's writes
 * commit on their own (progress is visible while a batch runs) and the slow network calls (Gemini, Cloudinary)
 * never sit inside a transaction.
 *
 * <p>The unit of work is one OPTION (a variant), because a photo belongs to exactly one option: the shots made from
 * an option's photo are filed under that same option and nowhere else.
 */
@Service
public class ImageCleanupWriter {

    /**
     * What a run works from. {@code displayName} is the product's name plus the option's when it has a meaningful one —
     * what the AI is told it is looking at. {@code referenceWasMain}: the photo was the option's main one when the run
     * started, which is what lets an immediate run notice that staff replaced it meanwhile.
     */
    public record Target(UUID productId, UUID variantId, String displayName, UUID referenceImageId, String referenceUrl,
                         boolean referenceWasMain) {}

    /** A Cloudinary file an undone batch no longer needs: its stored id, and the link photos showed it under. */
    public record Released(String url, String publicId) {}

    public record Generated(String url, String publicId, String prompt, PhotoView view) {}

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final ImageCleanupBatchRepository batches;
    private final ImageCleanupItemRepository items;
    private final ImageDraftRepository drafts;
    private final ApplicationEventPublisher events;

    public ImageCleanupWriter(ProductRepository products, ProductVariantRepository variants,
                              ProductImageRepository images, ImageCleanupBatchRepository batches,
                              ImageCleanupItemRepository items, ImageDraftRepository drafts,
                              ApplicationEventPublisher events) {
        this.products = products;
        this.variants = variants;
        this.images = images;
        this.batches = batches;
        this.items = items;
        this.drafts = drafts;
        this.events = events;
    }

    /**
     * The real photo to work from for one option, if it still qualifies (live option of a live product).
     *
     * @param sourceImageId the photo staff chose; must be one of the option's own and not AI-made. Null picks the
     *                      option's main real photo (else its first real one).
     * @param skipIfAlreadyCleaned a bulk run leaves an option alone once it has an AI photo; a run staff start on a
     *                      chosen option does not (they may well want more views, or to try again).
     */
    @Transactional(readOnly = true)
    public Optional<Target> targetFor(UUID variantId, UUID sourceImageId, boolean skipIfAlreadyCleaned) {
        ProductVariant variant = variants.findById(variantId).filter(v -> !v.isDeleted()).orElse(null);
        if (variant == null) {
            return Optional.empty();
        }
        Product product = variant.getProduct();
        if (product.isDeleted()) {
            return Optional.empty();
        }
        List<ProductImage> own = images.findByVariantIdOrderBySortOrderAsc(variantId);
        if (skipIfAlreadyCleaned && own.stream().anyMatch(i -> i.getSource() == ProductImage.ImageSource.AI_CLEANED)) {
            return Optional.empty();
        }
        ProductImage reference = sourceImageId != null
                ? own.stream().filter(i -> i.getId().equals(sourceImageId)
                        && i.getSource() != ProductImage.ImageSource.AI_CLEANED).findFirst().orElse(null)
                : own.stream().filter(i -> i.getSource() != ProductImage.ImageSource.AI_CLEANED)
                        .min(Comparator.comparing((ProductImage i) -> !i.isPrimary()).thenComparingInt(ProductImage::getSortOrder))
                        .orElse(null);
        if (reference == null) {
            return Optional.empty();
        }
        String displayName = CatalogVariantCardDto.isMeaninglessName(variant.getVariantName())
                ? product.getName() : product.getName() + " — " + variant.getVariantName();
        return Optional.of(new Target(product.getId(), variantId, displayName, reference.getId(), reference.getUrl(),
                reference.isPrimary()));
    }

    /**
     * Publishes straight away: the new photos go first in the option's gallery (the first becomes its main photo) and
     * the original stays after them, never discarded. Skips (reports false) if the photo this was made from was the
     * main one and has since been replaced, so a newer manual edit is not overwritten by a cleaned copy of a stale photo.
     */
    @Transactional
    public boolean recordSuccess(UUID batchId, Target target, List<Generated> generated) {
        ProductImage reference = images.findById(target.referenceImageId()).orElse(null);
        if (reference == null || !target.variantId().equals(PhotoOwnership.ownerOf(reference))
                || (target.referenceWasMain() && !reference.isPrimary())) {
            return false;
        }
        List<ProductImage> existing = images.findByVariantIdOrderBySortOrderAsc(target.variantId());
        for (ProductImage e : existing) {
            e.setSortOrder(e.getSortOrder() + generated.size());
            e.setPrimary(false);
        }
        images.saveAll(existing);

        Product product = products.getReferenceById(target.productId());
        ProductVariant option = variants.getReferenceById(target.variantId());
        List<ProductImage> created = new ArrayList<>();
        for (int i = 0; i < generated.size(); i++) {
            ProductImage image = new ProductImage();
            image.setProduct(product);
            image.setVariant(option);
            image.setUrl(generated.get(i).url());
            image.setAltText(target.displayName());
            image.setSortOrder(i);
            image.setPrimary(i == 0);
            image.setSource(ProductImage.ImageSource.AI_CLEANED);
            created.add(image);
        }
        images.saveAll(created);

        for (int i = 0; i < created.size(); i++) {
            ImageCleanupItem item = new ImageCleanupItem();
            item.setBatchId(batchId);
            item.setProductId(target.productId());
            item.setProductImageId(created.get(i).getId());
            item.setReferenceImageId(target.referenceImageId());
            item.setCloudinaryPublicId(generated.get(i).publicId());
            item.setPromptUsed(generated.get(i).prompt());
            items.save(item);
        }
        bump(batchId, 1, 0, null);
        return true;
    }

    /** Holds the new photos as drafts for a person to approve; nothing reaches the shop. */
    @Transactional
    public void recordDrafts(UUID batchId, Target target, List<Generated> generated, String note) {
        for (Generated g : generated) {
            ImageDraft draft = new ImageDraft();
            draft.setBatchId(batchId);
            draft.setProductId(target.productId());
            draft.setVariantId(target.variantId());
            draft.setReferenceImageId(target.referenceImageId());
            draft.setViewKey(g.view().name());
            draft.setNote(note);
            draft.setUrl(g.url());
            draft.setCloudinaryPublicId(g.publicId());
            draft.setPromptUsed(g.prompt());
            drafts.save(draft);
        }
        bump(batchId, 1, 0, null);
    }

    @Transactional
    public void recordFailure(UUID batchId, String reason) {
        bump(batchId, 0, 1, reason);
    }

    @Transactional
    public void finalizeBatch(UUID batchId, ImageCleanupBatch.Status status, String error) {
        batches.findById(batchId).ifPresent(b -> {
            b.setStatus(status);
            if (error != null) {
                b.setLastError(truncate(error));
            }
            batches.save(b);
        });
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
    }

    /**
     * Undo: remove what the batch published (and make each original photo its owner's main photo again) and discard
     * the drafts of it that are still waiting. Returns the Cloudinary files to delete, each with the link it was shown
     * under, so the caller can leave alone one that a duplicated product's photo still points at.
     */
    @Transactional
    public List<Released> undo(UUID batchId) {
        List<ImageCleanupItem> made = items.findByBatchId(batchId);
        List<Released> publicIds = new ArrayList<>();
        for (ImageCleanupItem item : made) {
            String url = images.findById(item.getProductImageId()).map(image -> {
                images.delete(image);
                return image.getUrl();
            }).orElse(null);
            if (item.getCloudinaryPublicId() != null) {
                publicIds.add(new Released(url, item.getCloudinaryPublicId()));
            }
        }
        images.flush();
        for (UUID referenceId : made.stream().map(ImageCleanupItem::getReferenceImageId).distinct().toList()) {
            ProductImage reference = images.findById(referenceId).orElse(null);
            if (reference == null) {
                continue; // the original was removed since; nothing to put back
            }
            UUID owner = PhotoOwnership.ownerOf(reference);
            List<ProductImage> remaining = owner != null
                    ? images.findByVariantIdOrderBySortOrderAsc(owner)
                    : PhotoOwnership.sameOwner(images.findByProductIdOrderBySortOrderAsc(reference.getProduct().getId()), null);
            int order = 0;
            for (ProductImage image : remaining) {
                image.setSortOrder(order++);
                image.setPrimary(image.getId().equals(referenceId));
            }
            images.saveAll(remaining);
        }
        List<ImageDraft> waiting = drafts.findByBatchId(batchId);
        for (ImageDraft draft : waiting) {
            if (draft.getCloudinaryPublicId() != null) {
                publicIds.add(new Released(draft.getUrl(), draft.getCloudinaryPublicId()));
            }
        }
        drafts.deleteAll(waiting);
        batches.findById(batchId).ifPresent(b -> {
            b.setStatus(ImageCleanupBatch.Status.DELETED);
            b.setDeletedAt(java.time.Instant.now());
            batches.save(b);
        });
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
        return publicIds;
    }

    private void bump(UUID batchId, int succeeded, int failed, String error) {
        batches.findById(batchId).ifPresent(b -> {
            b.setSucceededCount(b.getSucceededCount() + succeeded);
            b.setFailedCount(b.getFailedCount() + failed);
            if (error != null) {
                b.setLastError(truncate(error));
            }
            batches.save(b);
        });
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
    }

    private static String truncate(String s) {
        return s.length() <= 600 ? s : s.substring(0, 597) + "...";
    }
}
