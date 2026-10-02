package com.nbh.backend.imagecleanup;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.common.event.DataChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional database half of a cleanup run, a separate bean from the {@code @Async} runner so each
 * product's writes commit on their own (progress is visible while a batch runs) and the slow network calls
 * (Gemini, Cloudinary) never sit inside a transaction.
 */
@Service
public class ImageCleanupWriter {

    public record Target(UUID productId, String productName, UUID referenceImageId, String referenceUrl) {}

    public record Generated(String url, String publicId, String prompt) {}

    private final ProductRepository products;
    private final ProductImageRepository images;
    private final ImageCleanupBatchRepository batches;
    private final ImageCleanupItemRepository items;
    private final ApplicationEventPublisher events;

    public ImageCleanupWriter(ProductRepository products, ProductImageRepository images,
                              ImageCleanupBatchRepository batches, ImageCleanupItemRepository items,
                              ApplicationEventPublisher events) {
        this.products = products;
        this.images = images;
        this.batches = batches;
        this.items = items;
        this.events = events;
    }

    /** The product's current product-level primary photo, if it still qualifies (live, not already AI-made). */
    @Transactional(readOnly = true)
    public Optional<Target> targetFor(UUID productId) {
        Optional<Product> product = products.findById(productId).filter(p -> !p.isDeleted());
        if (product.isEmpty()) {
            return Optional.empty();
        }
        List<ProductImage> all = images.findByProductIdOrderBySortOrderAsc(productId);
        if (all.stream().anyMatch(i -> i.getSource() == ProductImage.ImageSource.AI_CLEANED)) {
            return Optional.empty();
        }
        return all.stream()
                .filter(i -> i.isPrimary() && i.getVariant() == null)
                .findFirst()
                .map(i -> new Target(productId, product.get().getName(), i.getId(), i.getUrl()));
    }

    /**
     * Puts the cleaned images first in the gallery (the first becomes the primary) and keeps the original photo
     * after them — never discarded. Skips (reports false) if the primary photo changed while the batch ran, so a
     * newer manual edit is not overwritten by a cleaned copy of a stale photo.
     */
    @Transactional
    public boolean recordSuccess(UUID batchId, Target target, List<Generated> generated) {
        ProductImage reference = images.findById(target.referenceImageId()).orElse(null);
        if (reference == null || !reference.isPrimary()) {
            return false;
        }
        List<ProductImage> existing = images.findByProductIdOrderBySortOrderAsc(target.productId());
        for (ProductImage e : existing) {
            e.setSortOrder(e.getSortOrder() + generated.size());
            e.setPrimary(false);
        }
        images.saveAll(existing);

        Product product = products.getReferenceById(target.productId());
        List<ProductImage> created = new ArrayList<>();
        for (int i = 0; i < generated.size(); i++) {
            ProductImage image = new ProductImage();
            image.setProduct(product);
            image.setUrl(generated.get(i).url());
            image.setAltText(target.productName());
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

    /** Undo: remove the images the batch made and make each original photo the primary again. */
    @Transactional
    public List<String> undo(UUID batchId) {
        List<ImageCleanupItem> made = items.findByBatchId(batchId);
        List<String> publicIds = new ArrayList<>();
        for (ImageCleanupItem item : made) {
            images.findById(item.getProductImageId()).ifPresent(images::delete);
            if (item.getCloudinaryPublicId() != null) {
                publicIds.add(item.getCloudinaryPublicId());
            }
        }
        images.flush();
        for (UUID productId : made.stream().map(ImageCleanupItem::getProductId).distinct().toList()) {
            UUID referenceId = made.stream().filter(i -> i.getProductId().equals(productId))
                    .map(ImageCleanupItem::getReferenceImageId).findFirst().orElse(null);
            List<ProductImage> remaining = images.findByProductIdOrderBySortOrderAsc(productId);
            int order = 0;
            for (ProductImage image : remaining) {
                image.setSortOrder(order++);
                image.setPrimary(image.getId().equals(referenceId));
            }
            if (remaining.stream().noneMatch(ProductImage::isPrimary) && !remaining.isEmpty()) {
                remaining.get(0).setPrimary(true);
            }
            images.saveAll(remaining);
        }
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
