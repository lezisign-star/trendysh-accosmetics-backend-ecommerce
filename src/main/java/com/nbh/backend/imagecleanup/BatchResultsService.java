package com.nbh.backend.imagecleanup;

import com.nbh.backend.catalog.dto.CatalogVariantCardDto;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a batch actually made, product by product, so staff can look at the pictures instead of a bare "completed":
 * for each product its options, each with the real photo it started from and every photo the run produced (already in
 * the shop, or waiting for approval), plus the description drafted for it if the run wrote one.
 */
@Service
public class BatchResultsService {

    /** {@code live}: already in the shop; false: a draft nobody can see yet. {@code label} is the view for a draft. */
    public record Photo(String url, String label, boolean live) {}

    public record OptionResult(UUID variantId, String optionName, String referenceUrl, List<Photo> photos) {}

    public record ProductResult(UUID productId, String productName, List<OptionResult> options,
                                DescriptionDraftService.DraftDto description) {}

    private final ImageCleanupBatchRepository batches;
    private final ImageCleanupItemRepository items;
    private final ImageDraftRepository drafts;
    private final ProductImageRepository images;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final DescriptionDraftService descriptions;

    public BatchResultsService(ImageCleanupBatchRepository batches, ImageCleanupItemRepository items,
                               ImageDraftRepository drafts, ProductImageRepository images, ProductRepository products,
                               ProductVariantRepository variants, DescriptionDraftService descriptions) {
        this.batches = batches;
        this.items = items;
        this.drafts = drafts;
        this.images = images;
        this.products = products;
        this.variants = variants;
        this.descriptions = descriptions;
    }

    @Transactional(readOnly = true)
    public List<ProductResult> results(UUID batchId) {
        if (!batches.existsById(batchId)) {
            throw new NotFoundException("No cleanup batch: " + batchId);
        }
        List<ImageCleanupItem> made = items.findByBatchId(batchId);
        List<ImageDraft> waiting = drafts.findByBatchId(batchId);
        List<DescriptionDraftService.DraftDto> described = descriptions.forBatch(batchId);

        Map<UUID, ProductImage> madeImages = images.findAllById(
                made.stream().map(ImageCleanupItem::getProductImageId).toList()).stream()
                .collect(Collectors.toMap(ProductImage::getId, Function.identity()));
        List<UUID> referenceIds = new ArrayList<>();
        made.forEach(i -> referenceIds.add(i.getReferenceImageId()));
        waiting.forEach(d -> referenceIds.add(d.getReferenceImageId()));
        Map<UUID, ProductImage> references = images.findAllById(referenceIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(ProductImage::getId, Function.identity()));

        // product -> option -> photos, in the order things were made.
        Map<UUID, Map<UUID, List<Photo>>> grouped = new LinkedHashMap<>();
        Map<UUID, Map<UUID, String>> referenceUrl = new LinkedHashMap<>();
        for (ImageCleanupItem item : made) {
            ProductImage photo = madeImages.get(item.getProductImageId());
            if (photo == null || photo.getVariant() == null) {
                continue; // removed since, or an old shared photo
            }
            UUID variantId = photo.getVariant().getId();
            grouped.computeIfAbsent(item.getProductId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(variantId, k -> new ArrayList<>()).add(new Photo(photo.getUrl(), "In the shop", true));
            ProductImage ref = references.get(item.getReferenceImageId());
            if (ref != null) {
                referenceUrl.computeIfAbsent(item.getProductId(), k -> new LinkedHashMap<>())
                        .putIfAbsent(variantId, ref.getUrl());
            }
        }
        for (ImageDraft draft : waiting) {
            String label = PhotoView.parse(draft.getViewKey()).map(PhotoView::label).orElse(draft.getViewKey());
            grouped.computeIfAbsent(draft.getProductId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(draft.getVariantId(), k -> new ArrayList<>())
                    .add(new Photo(draft.getUrl(), label + " (waiting for approval)", false));
            ProductImage ref = references.get(draft.getReferenceImageId());
            if (ref != null) {
                referenceUrl.computeIfAbsent(draft.getProductId(), k -> new LinkedHashMap<>())
                        .putIfAbsent(draft.getVariantId(), ref.getUrl());
            }
        }
        // A description can exist for a product whose photos all failed.
        described.forEach(d -> grouped.computeIfAbsent(d.productId(), k -> new LinkedHashMap<>()));

        Map<UUID, Product> productById = products.findAllById(grouped.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<UUID> variantIds = grouped.values().stream().flatMap(m -> m.keySet().stream()).distinct().toList();
        Map<UUID, ProductVariant> variantById = variants.findAllById(variantIds).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        Map<UUID, DescriptionDraftService.DraftDto> descriptionByProduct = described.stream()
                .collect(Collectors.toMap(DescriptionDraftService.DraftDto::productId, Function.identity(), (a, b) -> a));

        List<ProductResult> out = new ArrayList<>();
        grouped.forEach((productId, byOption) -> {
            Product product = productById.get(productId);
            if (product == null) {
                return;
            }
            List<OptionResult> options = new ArrayList<>();
            byOption.forEach((variantId, photos) -> {
                ProductVariant option = variantById.get(variantId);
                String name = option == null || CatalogVariantCardDto.isMeaninglessName(option.getVariantName())
                        ? null : option.getVariantName();
                String ref = referenceUrl.getOrDefault(productId, Map.of()).get(variantId);
                options.add(new OptionResult(variantId, name, ref, photos));
            });
            out.add(new ProductResult(productId, product.getName(), options, descriptionByProduct.get(productId)));
        });
        return out;
    }
}
