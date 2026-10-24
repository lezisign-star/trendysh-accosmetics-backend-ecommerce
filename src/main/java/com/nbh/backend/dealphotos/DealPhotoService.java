package com.nbh.backend.dealphotos;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.PhotoOwnership;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException;
import com.nbh.backend.imagecleanup.GeminiImageClient.Image;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deal pictures (owner, 2026-10-24): while a product is on a flash deal, Gemini makes a promotional picture of it from its
 * real photo; staff use it or not; the shop shows the used one on deal cards (the hero, Flash Deals, the Deals page) while
 * the deal lasts. A few are made at a time by {@link #runOnce()} so Gemini's quota lasts; staff can ask for another.
 * Nothing is held open while Gemini works.
 */
@Slf4j
@Service
public class DealPhotoService {

    /** Up to this many pictures each time the job runs. */
    static final int PER_RUN = 2;

    private final DealPhotoRepository photos;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final GeminiImageClient gemini;
    private final CloudinaryUploader cloudinary;
    private final ImageCleanupConfig geminiConfig;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final boolean enabled;

    public DealPhotoService(DealPhotoRepository photos, ProductVariantRepository variants, ProductImageRepository images,
                            GeminiImageClient gemini, CloudinaryUploader cloudinary, ImageCleanupConfig geminiConfig,
                            PlatformTransactionManager transactions, ApplicationEventPublisher events, Clock clock,
                            @Value("${app.deal-photos.enabled:true}") boolean enabled) {
        this.photos = photos;
        this.variants = variants;
        this.images = images;
        this.gemini = gemini;
        this.cloudinary = cloudinary;
        this.geminiConfig = geminiConfig;
        this.tx = new TransactionTemplate(transactions);
        this.events = events;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** What staff see of one picture: the product, its deal, its real photo beside the made one. */
    public record DealPhotoView(UUID id, UUID variantId, String productName, String category, BigDecimal sellingPrice,
                                BigDecimal dealPrice, Instant dealEndsAt, String originalUrl, String url, String status,
                                String note, Instant createdAt) {}

    /** What making a picture starts from, read before Gemini is asked. */
    record Inputs(UUID variantId, String name, String category, String photoUrl) {}

    // ---- the job ----

    @Scheduled(initialDelayString = "${app.deal-photos.initial-delay-ms:120000}",
            fixedDelayString = "${app.deal-photos.every-ms:600000}")
    public void runOnce() {
        if (!enabled || !geminiConfig.geminiConfigured() || !cloudinary.configured()) {
            return;
        }
        List<UUID> waiting = photos.variantsWaitingForAPicture(PER_RUN);
        for (UUID variantId : waiting) {
            try {
                make(variantId);
            } catch (QuotaReached e) {
                log.info("Deal pictures: Gemini's limit reached; trying again next run");
                return;
            } catch (RuntimeException e) {
                log.warn("Deal picture for variant {} failed: {}", variantId, e.getMessage());
            }
        }
    }

    /** Gemini's quota is used up: the job stops for this run without recording a failure, so it tries again later. */
    static class QuotaReached extends RuntimeException {
        QuotaReached(String message) {
            super(message);
        }
    }

    /**
     * Makes one picture for this product and files it as waiting for staff. A product with no photo, or a picture Gemini
     * could not make, is recorded as FAILED with the reason (so the job does not try it again on its own).
     */
    public DealPhotoView make(UUID variantId) {
        Inputs in = tx.execute(status -> inputs(variantId));
        if (in == null || in.photoUrl() == null) {
            return record(variantId, null, DealPhoto.Status.FAILED,
                    "The product has no photo to make a deal picture from.");
        }
        Image picture;
        try {
            picture = gemini.edit(gemini.download(in.photoUrl()), List.of(), prompt(in));
        } catch (GeminiException e) {
            if (e.isQuotaExceeded()) {
                throw new QuotaReached(e.getMessage());
            }
            return record(variantId, null, DealPhoto.Status.FAILED, e.getMessage());
        }
        String url = cloudinary.upload(picture.bytes(), "deals", "deal-" + variantId + "-" + clock.millis()).url();
        return record(variantId, url, DealPhoto.Status.PENDING, null);
    }

    /** As {@link #make}, asked for by staff ("Make another"): a picture Gemini cannot make is said plainly. */
    public DealPhotoView makeAnother(UUID variantId) {
        if (!geminiConfig.geminiConfigured() || !cloudinary.configured()) {
            throw new UnprocessableException("Gemini or photo storage is not set up, so a picture can't be made.");
        }
        try {
            DealPhotoView made = make(variantId);
            if ("FAILED".equals(made.status())) {
                throw new UnprocessableException("Gemini could not make the picture this time. (" + made.note() + ")");
            }
            return made;
        } catch (QuotaReached e) {
            throw new UnprocessableException("Gemini's limit for now is used up. Try again later.");
        }
    }

    Inputs inputs(UUID variantId) {
        ProductVariant v = variants.findById(variantId).orElse(null);
        if (v == null) {
            return null;
        }
        var product = v.getProduct();
        List<ProductImage> all = images.findByProductIdInOrderBySortOrderAsc(List.of(product.getId()));
        Map<UUID, Long> counts = PhotoOwnership.optionCounts(variants.findByProductIdInAndDeletedFalse(List.of(product.getId())));
        String photo = PhotoOwnership.coverUrlByVariant(List.of(v), all, counts).get(v.getId());
        String option = v.shortOption();
        String name = option == null || option.isBlank() || option.equalsIgnoreCase("Default")
                ? product.getName() : product.getName() + " (" + option + ")";
        String category = product.getCategories().stream().map(Category::getName).findFirst().orElse("beauty supplies");
        return new Inputs(variantId, name, category, photo);
    }

    /** What Gemini is asked: the real product, kept exactly as it is, in a scene that sells it. No words: the shop adds those. */
    static String prompt(Inputs in) {
        return "The photo shows a real product sold by a beauty-supply shop in Nairobi: " + in.name() + " (" + in.category()
                + "). Make a promotional photo of THIS exact product for the shop's deals: keep it identical to the photo, the"
                + " same shape, colours, packaging and label text, and do not add other products of the same kind. Place it as"
                + " the clear hero of a styled scene that suits " + in.category() + ", in soft pink, cream and warm gold tones,"
                + " with a few tasteful natural props and a gently blurred background, the product sharp, large in the frame,"
                + " fully visible and centred. Landscape, 4:3. No text, prices, badges, logos, watermarks or hands.";
    }

    private DealPhotoView record(UUID variantId, String url, DealPhoto.Status status, String note) {
        return tx.execute(s -> {
            ProductVariant v = variants.findById(variantId)
                    .orElseThrow(() -> new NotFoundException("No product: " + variantId));
            DealPhoto photo = new DealPhoto();
            photo.setVariant(v);
            photo.setUrl(url);
            photo.setStatus(status);
            photo.setNote(note);
            DealPhoto saved = photos.save(photo);
            if (status == DealPhoto.Status.PENDING) {
                events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
            }
            return view(saved);
        });
    }

    // ---- staff ----

    public List<DealPhotoView> waiting() {
        return tx.execute(s -> photos.findByStatusOrderByCreatedAtDesc(DealPhoto.Status.PENDING).stream().map(this::view).toList());
    }

    public long waitingCount() {
        return photos.countByStatus(DealPhoto.Status.PENDING);
    }

    /** Uses this picture on the product's deal cards; one the product had before is set aside. */
    public DealPhotoView approve(UUID id, String staffName) {
        return tx.execute(s -> {
            DealPhoto photo = waitingOrThrow(id);
            for (DealPhoto earlier : photos.findByVariantIdAndStatus(photo.getVariant().getId(), DealPhoto.Status.APPROVED)) {
                earlier.setStatus(DealPhoto.Status.REJECTED);
            }
            decide(photo, DealPhoto.Status.APPROVED, staffName);
            events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
            return view(photo);
        });
    }

    public DealPhotoView reject(UUID id, String staffName) {
        return tx.execute(s -> {
            DealPhoto photo = waitingOrThrow(id);
            decide(photo, DealPhoto.Status.REJECTED, staffName);
            return view(photo);
        });
    }

    private DealPhoto waitingOrThrow(UUID id) {
        DealPhoto photo = photos.findById(id).orElseThrow(() -> new NotFoundException("No deal picture: " + id));
        if (photo.getStatus() != DealPhoto.Status.PENDING) {
            throw new UnprocessableException("That picture has already been decided on.");
        }
        return photo;
    }

    private void decide(DealPhoto photo, DealPhoto.Status status, String staffName) {
        photo.setStatus(status);
        photo.setDecidedBy(staffName);
        photo.setDecidedAt(Instant.now(clock));
        photos.save(photo);
    }

    // ---- the shop ----

    /** The used deal picture of each of these products that has one (the catalogue shows it only while the deal is live). */
    public Map<UUID, String> approvedUrls(Collection<UUID> variantIds) {
        Map<UUID, String> out = new HashMap<>();
        if (variantIds.isEmpty()) {
            return out;
        }
        for (DealPhoto photo : photos.findByStatusAndVariantIdIn(DealPhoto.Status.APPROVED, variantIds)) {
            out.put(photo.getVariant().getId(), photo.getUrl());
        }
        return out;
    }

    private DealPhotoView view(DealPhoto photo) {
        ProductVariant v = photo.getVariant();
        Inputs in = inputs(v.getId());
        return new DealPhotoView(photo.getId(), v.getId(), in == null ? v.getProduct().getName() : in.name(),
                in == null ? null : in.category(), v.getSellingPrice(), v.getDealPrice(), v.getDealEndsAt(),
                in == null ? null : in.photoUrl(), photo.getUrl(), photo.getStatus().name(), photo.getNote(),
                photo.getCreatedAt());
    }
}
