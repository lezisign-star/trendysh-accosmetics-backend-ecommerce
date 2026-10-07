package com.nbh.backend.imagecleanup;

import com.nbh.backend.catalog.dto.AdminImageDto;
import com.nbh.backend.catalog.dto.CatalogVariantCardDto;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.ImageAssetCleaner;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The review step for AI photos (owner, 2026-10-07): a photo made in "preview first" mode waits here, invisible to the
 * shop, until staff approve it (it becomes an ordinary photo of the option it was made for), reject it (it and its
 * Cloudinary file are deleted) or let it lapse (drafts nobody decided on are deleted after a couple of weeks, so the
 * Cloudinary account does not fill with photos nobody wanted).
 */
@Slf4j
@Service
public class ImageDraftService {

    /** A draft as the review screen needs it: the photo, what it was made from, and who/what it is for. */
    public record DraftDto(UUID id, UUID batchId, UUID productId, String productName, UUID variantId, String optionName,
                           UUID referenceImageId, String referenceUrl, String url, String viewKey, String viewLabel,
                           String note, Instant createdAt) {}

    private final ImageDraftRepository drafts;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final ImageCleanupItemRepository items;
    private final ImageAssetCleaner assetCleaner;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int draftDays;

    public ImageDraftService(ImageDraftRepository drafts, ProductRepository products, ProductVariantRepository variants,
                             ProductImageRepository images, ImageCleanupItemRepository items,
                             ImageAssetCleaner assetCleaner, ApplicationEventPublisher events, Clock clock,
                             @Value("${app.image-cleanup.draft-days:14}") int draftDays) {
        this.drafts = drafts;
        this.products = products;
        this.variants = variants;
        this.images = images;
        this.items = items;
        this.assetCleaner = assetCleaner;
        this.events = events;
        this.clock = clock;
        this.draftDays = draftDays;
    }

    public int draftDays() {
        return draftDays;
    }

    /** Drafts waiting for a decision, newest first; for one product when given. Drafts of a removed product or option are left out. */
    @Transactional(readOnly = true)
    public List<DraftDto> list(UUID productId) {
        List<ImageDraft> waiting = productId == null
                ? drafts.findAllByOrderByCreatedAtDesc() : drafts.findByProductIdOrderByCreatedAtDesc(productId);
        if (waiting.isEmpty()) {
            return List.of();
        }
        Map<UUID, Product> productById = products.findAllById(
                waiting.stream().map(ImageDraft::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, ProductVariant> optionById = variants.findAllById(
                waiting.stream().map(ImageDraft::getVariantId).distinct().toList()).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        Map<UUID, ProductImage> referenceById = images.findAllById(
                waiting.stream().map(ImageDraft::getReferenceImageId).distinct().toList()).stream()
                .collect(Collectors.toMap(ProductImage::getId, Function.identity()));
        return waiting.stream().flatMap(d -> {
            Product product = productById.get(d.getProductId());
            ProductVariant option = optionById.get(d.getVariantId());
            if (product == null || product.isDeleted() || option == null || option.isDeleted()) {
                return java.util.stream.Stream.<DraftDto>empty();
            }
            ProductImage reference = referenceById.get(d.getReferenceImageId());
            String optionName = CatalogVariantCardDto.isMeaninglessName(option.getVariantName()) ? null : option.getVariantName();
            String viewLabel = PhotoView.parse(d.getViewKey()).map(PhotoView::label).orElse(d.getViewKey());
            return java.util.stream.Stream.of(new DraftDto(d.getId(), d.getBatchId(), product.getId(), product.getName(),
                    option.getId(), optionName, d.getReferenceImageId(), reference == null ? null : reference.getUrl(),
                    d.getUrl(), d.getViewKey(), viewLabel, d.getNote(), d.getCreatedAt()));
        }).toList();
    }

    public long pendingCount() {
        return drafts.count();
    }

    /**
     * Makes the draft an ordinary photo of its option. It goes to the end of the option's gallery, or first and as the
     * main photo when {@code makeMain} (or when the option had no photos). It is recorded under the batch it came
     * from, so undoing that batch removes it again.
     */
    @Transactional
    public AdminImageDto approve(UUID draftId, boolean makeMain) {
        ImageDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new NotFoundException("That photo is no longer waiting for review."));
        ProductVariant option = variants.findById(draft.getVariantId()).filter(v -> !v.isDeleted()).orElse(null);
        Product product = products.findById(draft.getProductId()).filter(p -> !p.isDeleted()).orElse(null);
        if (option == null || product == null) {
            throw new UnprocessableException("That product or option has been removed, so this photo has nowhere to go. Reject it.");
        }
        List<ProductImage> own = images.findByVariantIdOrderBySortOrderAsc(option.getId());
        boolean main = makeMain || own.isEmpty();

        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setVariant(option);
        image.setUrl(draft.getUrl());
        image.setAltText(CatalogVariantCardDto.isMeaninglessName(option.getVariantName())
                ? product.getName() : product.getName() + " — " + option.getVariantName());
        image.setSource(ProductImage.ImageSource.AI_CLEANED);
        if (main) {
            for (ProductImage e : own) {
                e.setSortOrder(e.getSortOrder() + 1);
                e.setPrimary(false);
            }
            images.saveAll(own);
            image.setSortOrder(0);
            image.setPrimary(true);
        } else {
            image.setSortOrder(own.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1);
            image.setPrimary(false);
        }
        image = images.save(image);

        ImageCleanupItem item = new ImageCleanupItem();
        item.setBatchId(draft.getBatchId());
        item.setProductId(product.getId());
        item.setProductImageId(image.getId());
        item.setReferenceImageId(draft.getReferenceImageId());
        item.setCloudinaryPublicId(draft.getCloudinaryPublicId());
        item.setPromptUsed(draft.getPromptUsed());
        items.save(item);

        drafts.delete(draft);
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
        return AdminImageDto.from(image);
    }

    /** Throws the draft away, and its file from Cloudinary once that has committed. */
    @Transactional
    public void reject(UUID draftId) {
        ImageDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new NotFoundException("That photo is no longer waiting for review."));
        drafts.delete(draft);
        assetCleaner.releaseAfterCommit(List.of(draft.getUrl()));
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
    }

    /** Drafts nobody decided on are removed after {@code draftDays}, with their Cloudinary files. */
    @Scheduled(cron = "0 40 3 * * *")
    @Transactional
    public void expireOld() {
        List<ImageDraft> lapsed = drafts.findByCreatedAtBefore(Instant.now(clock).minus(Duration.ofDays(draftDays)));
        if (lapsed.isEmpty()) {
            return;
        }
        drafts.deleteAll(lapsed);
        assetCleaner.releaseAfterCommit(lapsed.stream().map(ImageDraft::getUrl).toList());
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
        log.info("Removed {} AI photo draft(s) nobody decided on within {} days", lapsed.size(), draftDays);
    }
}
