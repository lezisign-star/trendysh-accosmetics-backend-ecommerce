package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.dto.AdminImageDto;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.imagecleanup.ImageCleanupPromptBuilder;
import com.nbh.backend.imagecleanup.WatermarkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * "Edit this variant's photo with a prompt" (owner, 2026-10-14: after a variant has been generated, staff must be able
 * to change its image by taking another one or by describing an edit). The photo studio only starts from a real
 * photo, and the photos a job makes are AI-made, so this is its own small path: take the variant's current photo, ask
 * the AI for exactly the change described, store the result and make it the variant's main photo. The photo it came
 * from stays in the variant's gallery (second), so the change can be undone by making that one main again.
 */
@Slf4j
@Service
public class VariantPhotoEditor {

    /** The new photo and the one it replaced as main (null when there was none), for an "Undo". */
    public record Edited(AdminImageDto image, UUID previousMainId) {}

    private final GeminiImageClient gemini;
    private final CloudinaryUploader cloudinary;
    private final WatermarkService watermark;
    private final ImageCleanupConfig config;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public VariantPhotoEditor(GeminiImageClient gemini, CloudinaryUploader cloudinary, WatermarkService watermark,
                              ImageCleanupConfig config, ProductVariantRepository variants,
                              ProductImageRepository images, ApplicationEventPublisher events,
                              PlatformTransactionManager txManager) {
        this.gemini = gemini;
        this.cloudinary = cloudinary;
        this.watermark = watermark;
        this.config = config;
        this.variants = variants;
        this.images = images;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * @param imageId the photo to start from; null = the variant's main one
     * @param note    what to change, in staff's words
     */
    public Edited edit(UUID productId, UUID variantId, UUID imageId, String note) {
        String instruction = ImageCleanupPromptBuilder.cleanNote(note);
        if (instruction == null || instruction.isBlank()) {
            throw new UnprocessableException("Say what to change, for example \"make the cap gold\".");
        }
        if (!config.geminiConfigured()) {
            throw new UnprocessableException("The AI isn't set up yet — add the GEMINI_API_KEY before editing photos.");
        }
        if (!cloudinary.configured()) {
            throw new UnprocessableException("Cloudinary isn't set up yet — the photo can't be stored.");
        }
        // Read what is needed, then let go of the database while the AI works (it can take a minute).
        record Start(String url, String label) {}
        Start start = tx.execute(status -> {
            ProductVariant v = variants.findById(variantId).filter(x -> !x.isDeleted()).orElse(null);
            if (v == null || !v.getProduct().getId().equals(productId)) {
                throw new NotFoundException("That variant isn't on this product any more.");
            }
            List<ProductImage> own = images.findByVariantIdOrderBySortOrderAsc(variantId);
            ProductImage from = imageId != null
                    ? own.stream().filter(i -> i.getId().equals(imageId)).findFirst().orElse(null)
                    : own.stream().min(Comparator.comparing((ProductImage i) -> !i.isPrimary())
                            .thenComparingInt(ProductImage::getSortOrder)).orElse(null);
            if (from == null) {
                throw new UnprocessableException(imageId != null
                        ? "That photo isn't one of this variant's any more."
                        : "This variant has no photo to edit yet. Add one first.");
            }
            return new Start(from.getUrl(), v.displayLabel());
        });
        if (start == null) {
            throw new UnprocessableException("That didn't work. Try again.");
        }
        GeminiImageClient.Image edited;
        try {
            GeminiImageClient.Image source = gemini.download(start.url());
            edited = gemini.edit(source, prompt(instruction));
        } catch (GeminiImageClient.GeminiException e) {
            log.info("Editing a variant photo failed: {}", e.getMessage());
            throw new UnprocessableException(e.isQuotaExceeded()
                    ? "The AI is busy at the moment (its limit for now is used up). Try again in a few minutes."
                    : "The AI couldn't make that change: " + e.getMessage());
        }
        String publicId = variantId + "-edit-" + UUID.randomUUID().toString().substring(0, 8);
        String url = cloudinary.upload(watermark.stamp(edited.bytes()), "products", publicId).url();

        Edited result = tx.execute(status -> {
            ProductVariant v = variants.findById(variantId).filter(x -> !x.isDeleted()).orElse(null);
            if (v == null) {
                throw new NotFoundException("That variant was removed while the photo was being made.");
            }
            List<ProductImage> own = images.findByVariantIdOrderBySortOrderAsc(variantId);
            UUID previousMain = own.stream().filter(ProductImage::isPrimary).map(ProductImage::getId).findFirst()
                    .orElse(null);
            // The new photo goes first and is the main one; everything it was made from stays behind it.
            for (ProductImage e : own) {
                e.setSortOrder(e.getSortOrder() + 1);
                e.setPrimary(false);
            }
            images.saveAll(own);
            ProductImage image = new ProductImage();
            image.setProduct(v.getProduct());
            image.setVariant(v);
            image.setUrl(url);
            image.setAltText(v.displayLabel());
            image.setSortOrder(0);
            image.setPrimary(true);
            image.setSource(ProductImage.ImageSource.AI_CLEANED);
            return new Edited(AdminImageDto.from(images.save(image)), previousMain);
        });
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
        return result;
    }

    static String prompt(String instruction) {
        return "Edit this product photo exactly as the shop owner asks, and change nothing else.\n"
                + "Instruction: " + instruction + "\n"
                + "Keep the product's shape, its packaging, the printed text and logo, the framing and the background "
                + "exactly as they are unless the instruction says otherwise. Return only the edited photo.";
    }
}
