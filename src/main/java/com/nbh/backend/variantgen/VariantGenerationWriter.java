package com.nbh.backend.variantgen;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.ProductOptionsService;
import com.nbh.backend.catalog.service.ProductService;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.imagecleanup.ImageCleanupBatch;
import com.nbh.backend.imagecleanup.ImageCleanupBatchRepository;
import com.nbh.backend.imagecleanup.ImageCleanupItem;
import com.nbh.backend.imagecleanup.ImageCleanupItemRepository;
import com.nbh.backend.variantgen.VariantCombos.Combo;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** The database half of variant generation: each method is its own transaction, kept apart from Gemini/Cloudinary calls. */
@Service
public class VariantGenerationWriter {

    /** One option to create with what the planner wrote for it. */
    public record Planned(Combo combo, String swatchHex, String description) {}

    /** What a variant's photo is made from, and what it is called. */
    public record Source(UUID productId, UUID baseVariantId, String productName, UUID referenceImageId, String referenceUrl) {}

    public record NewVariant(UUID variantId, String displayName, String color, String size, String swatchHex) {}

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final ImageCleanupBatchRepository batches;
    private final ImageCleanupItemRepository items;
    private final ProductService productService;
    private final ApplicationEventPublisher events;
    private final ProductOptionsService productOptions;

    public VariantGenerationWriter(ProductRepository products, ProductVariantRepository variants,
                                   ProductImageRepository images, ImageCleanupBatchRepository batches,
                                   ImageCleanupItemRepository items, ProductService productService,
                                   ApplicationEventPublisher events, ProductOptionsService productOptions) {
        this.products = products;
        this.variants = variants;
        this.images = images;
        this.batches = batches;
        this.items = items;
        this.productService = productService;
        this.events = events;
        this.productOptions = productOptions;
    }

    /** The base variant (the chosen one, else the product's first live one) and the photo to work from. Empty if the product is gone. */
    @Transactional(readOnly = true)
    public Optional<Source> source(UUID productId, UUID baseVariantId) {
        Product product = products.findById(productId).filter(p -> !p.isDeleted()).orElse(null);
        if (product == null) {
            return Optional.empty();
        }
        List<ProductVariant> live = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId);
        ProductVariant base = baseVariantId == null ? live.stream().findFirst().orElse(null)
                : live.stream().filter(v -> v.getId().equals(baseVariantId)).findFirst().orElse(null);
        if (base == null) {
            return Optional.empty();
        }
        return Optional.of(sourceOf(product, base));
    }

    private Source sourceOf(Product product, ProductVariant base) {
        List<ProductImage> own = images.findByVariantIdOrderBySortOrderAsc(base.getId());
        ProductImage ref = own.stream().filter(ProductImage::isPrimary).findFirst().or(() -> own.stream().findFirst())
                .orElse(null);
        return new Source(product.getId(), base.getId(), product.getName(), ref == null ? null : ref.getId(),
                ref == null ? null : ref.getUrl());
    }

    /** Text the planner needs about the base. */
    public record BaseText(String productName, String baseOption, String description) {}

    @Transactional(readOnly = true)
    public BaseText baseText(UUID productId, UUID baseVariantId) {
        Product product = products.findById(productId).orElseThrow();
        ProductVariant base = variants.findById(baseVariantId).orElseThrow();
        String desc = product.getDescription() != null && !product.getDescription().isBlank()
                ? product.getDescription() : base.getDescription();
        String option = base.getOptionLabel() != null ? base.getOptionLabel()
                : com.nbh.backend.catalog.dto.CatalogVariantCardDto.isMeaninglessName(base.getVariantName())
                ? null : base.getVariantName();
        return new BaseText(product.getName(), option, desc);
    }

    /** Option labels already on the product (any case), so a re-run does not create duplicates. */
    @Transactional(readOnly = true)
    public List<String> existingLabels(UUID productId) {
        List<String> out = new ArrayList<>();
        for (ProductVariant v : variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId)) {
            if (v.getOptionLabel() != null) {
                out.add(v.getOptionLabel().toLowerCase(Locale.ROOT));
            }
            if (v.getVariantName() != null) {
                out.add(v.getVariantName().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * Creates the variants in one transaction: auto SKU, stock none yet, the shared price (else the base's), the base's
     * unit/weight/VAT. {@code active} false (approve first) keeps them hidden until the run is published. When
     * {@code baseOption} is given the base variant is named and labelled too. Returns them in order.
     */
    @Transactional
    public List<NewVariant> createVariants(UUID batchId, UUID productId, UUID baseVariantId, List<Planned> planned,
                                           BigDecimal price, boolean active, String baseOption, String baseSwatchHex) {
        Product product = products.findById(productId).orElseThrow();
        ProductVariant base = variants.findById(baseVariantId).orElseThrow();
        if (baseOption != null && !baseOption.isBlank()) {
            String label = baseOption.trim();
            base.setOptionLabel(label);
            if (baseSwatchHex != null && base.getSwatchHex() == null) {
                base.setSwatchHex(baseSwatchHex);
            }
            base.setVariantName(VariantCombos.variantName(product.getName(), label));
            variants.save(base);
        }
        int order = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId).stream()
                .mapToInt(ProductVariant::getSortOrder).max().orElse(-1) + 1;
        List<NewVariant> out = new ArrayList<>();
        for (Planned p : planned) {
            ProductVariant v = new ProductVariant();
            v.setProduct(product);
            v.setSku(productService.nextSku(product));
            String label = p.combo().label();
            v.setVariantName(VariantCombos.variantName(product.getName(), label));
            v.setOptionLabel(label);
            v.setOptionColor(p.combo().color());
            v.setOptionSize(p.combo().size());
            v.setSwatchHex(p.swatchHex());
            v.setDescription(p.description() != null ? p.description() : base.getDescription());
            v.setSellingPrice(price != null ? price : base.getSellingPrice());
            v.setCompareAtPrice(base.getCompareAtPrice());
            v.setStandardCost(base.getStandardCost());
            v.setUom(base.getUom());
            v.setWeightGrams(base.getWeightGrams());
            v.setVolumeMl(base.getVolumeMl());
            v.setVatRate(base.getVatRate());
            v.setVatExempt(base.isVatExempt());
            v.setActive(active);
            v.setSortOrder(order++);
            v.setGeneratedFromVariantId(baseVariantId);
            v.setGenerationBatchId(batchId);
            v = variants.save(v);
            out.add(new NewVariant(v.getId(), v.displayLabel(), p.combo().color(), p.combo().size(), p.swatchHex()));
        }
        // The product's colour/size list (V63) takes in the new options, so the shop's picker knows their order.
        productOptions.sync(productId);
        batches.findById(batchId).ifPresent(b -> {
            b.setRequestedCount(planned.size());
            batches.save(b);
        });
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
        return out;
    }

    /** What the photo prompt needs about an existing variant (for a retry). Empty when it is gone or was not generated. */
    @Transactional(readOnly = true)
    public Optional<RetryTarget> retryTarget(UUID variantId) {
        ProductVariant v = variants.findWithProductById(variantId).filter(x -> !x.isDeleted()).orElse(null);
        if (v == null || v.getGeneratedFromVariantId() == null || v.getProduct().isDeleted()) {
            return Optional.empty();
        }
        ProductVariant base = variants.findById(v.getGeneratedFromVariantId()).filter(x -> !x.isDeleted()).orElse(null);
        if (base == null) {
            return Optional.empty();
        }
        Source source = sourceOf(v.getProduct(), base);
        String note = v.getGenerationBatchId() == null ? null
                : batches.findById(v.getGenerationBatchId()).map(ImageCleanupBatch::getNote).orElse(null);
        return Optional.of(new RetryTarget(new NewVariant(v.getId(), v.displayLabel(), v.getOptionColor(),
                v.getOptionSize(), v.getSwatchHex()), source, note));
    }

    public record RetryTarget(NewVariant variant, Source source, String note) {}

    /** Puts the photo straight into the shop as the variant's main photo (and records it so the run can be undone). */
    @Transactional
    public void recordPhoto(UUID batchId, Source source, NewVariant variant, String url, String publicId, String prompt) {
        Product product = products.getReferenceById(source.productId());
        ProductVariant option = variants.getReferenceById(variant.variantId());
        List<ProductImage> own = images.findByVariantIdOrderBySortOrderAsc(variant.variantId());
        for (ProductImage e : own) {
            e.setSortOrder(e.getSortOrder() + 1);
            e.setPrimary(false);
        }
        images.saveAll(own);
        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setVariant(option);
        image.setUrl(url);
        image.setAltText(variant.displayName());
        image.setSortOrder(0);
        image.setPrimary(true);
        image.setSource(ProductImage.ImageSource.AI_CLEANED);
        image = images.save(image);
        if (source.referenceImageId() != null) {
            ImageCleanupItem item = new ImageCleanupItem();
            item.setBatchId(batchId);
            item.setProductId(source.productId());
            item.setProductImageId(image.getId());
            item.setReferenceImageId(source.referenceImageId());
            item.setCloudinaryPublicId(publicId);
            item.setPromptUsed(prompt);
            items.save(item);
        }
        batches.findById(batchId).ifPresent(b -> {
            b.setSucceededCount(b.getSucceededCount() + 1);
            batches.save(b);
        });
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
    }

    /** Makes every hidden variant of a run visible. Returns how many it changed. */
    @Transactional
    public int publishRun(UUID batchId) {
        int n = 0;
        for (ProductVariant v : variants.findByGenerationBatchId(batchId)) {
            if (!v.isDeleted() && !v.isActive()) {
                v.setActive(true);
                variants.save(v);
                n++;
            }
        }
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));
        return n;
    }
}
