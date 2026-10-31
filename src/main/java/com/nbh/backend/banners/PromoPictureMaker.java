package com.nbh.backend.banners;

import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException;
import com.nbh.backend.imagecleanup.GeminiImageClient.Image;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Makes one builder banner's picture in the background (owner, 2026-10-31), so staff can leave the page: Gemini starts
 * from the featured products' real photos (else a photo of the linked category, else any shop photo) and draws a picture
 * shaped for the banner's place with the word area left calm ({@link PromoBannerPrompts}); it is kept on Cloudinary and
 * the picture marked ready, or failed with Gemini's reason.
 */
@Slf4j
@Service
public class PromoPictureMaker {

    /** Real photos Gemini is shown, the first edited and the rest to match. */
    private static final int MAX_REFERENCES = 3;

    private final PromoBannerRepository banners;
    private final PromoBannerPictureRepository pictures;
    private final OccasionRepository occasions;
    private final GeminiImageClient images;
    private final CloudinaryUploader cloudinary;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PromoPictureMaker(PromoBannerRepository banners, PromoBannerPictureRepository pictures,
                             OccasionRepository occasions, GeminiImageClient images, CloudinaryUploader cloudinary,
                             EntityManager em, PlatformTransactionManager transactions, Clock clock) {
        this.banners = banners;
        this.pictures = pictures;
        this.occasions = occasions;
        this.images = images;
        this.cloudinary = cloudinary;
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** What one picture is made from. */
    record Job(PromoBanner.Placement placement, List<String> photoUrls, List<String> productNames, String brief,
               String theme, String bannerName) {}

    @Async
    public void make(UUID pictureId) {
        makeNow(pictureId);
    }

    /** The work itself, on the caller's thread (tests call it directly). */
    void makeNow(UUID pictureId) {
        Job job;
        try {
            job = tx.execute(s -> job(pictureId));
        } catch (RuntimeException e) {
            finish(pictureId, null, "The banner could not be read: " + e.getMessage());
            return;
        }
        if (job == null) return;
        if (job.photoUrls().isEmpty()) {
            finish(pictureId, null, "There is no product photo to start from. Feature a product with a photo first.");
            return;
        }
        try {
            List<Image> refs = new ArrayList<>();
            for (String url : job.photoUrls()) refs.add(images.download(url));
            String prompt = PromoBannerPrompts.picture(job.placement(), job.productNames(), job.brief(), job.theme());
            Image picture = images.edit(refs.get(0), refs.subList(1, refs.size()), prompt,
                    PromoBannerPrompts.aspectRatio(job.placement()));
            String url = cloudinary.upload(picture.bytes(), "promo-banners",
                    "promo-" + job.placement().name().toLowerCase(Locale.ROOT) + "-" + clock.millis()).url();
            finish(pictureId, url, null);
        } catch (GeminiException e) {
            finish(pictureId, null, e.isQuotaExceeded()
                    ? "Gemini's limit for now is used up. Try again later. (" + e.getMessage() + ")" : e.getMessage());
        } catch (RuntimeException e) {
            finish(pictureId, null, "The picture could not be made: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Job job(UUID pictureId) {
        PromoBannerPicture p = pictures.findById(pictureId).orElse(null);
        if (p == null || p.getStatus() != PromoBannerPicture.Status.MAKING) return null;
        PromoBanner b = banners.findById(p.getBannerId()).orElseThrow();
        List<String> urls = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                SELECT p.name || COALESCE(' (' || NULLIF(v.option_label, '') || ')', ''),
                       (SELECT i.url FROM product_image i
                         WHERE i.variant_id = v.id OR (i.variant_id IS NULL AND i.product_id = v.product_id)
                         ORDER BY (i.variant_id IS NULL), i.is_primary DESC, i.sort_order LIMIT 1)
                FROM promo_banner_product bp
                JOIN product_variant v ON v.id = bp.variant_id
                JOIN product p ON p.id = v.product_id
                WHERE bp.banner_id = :b ORDER BY bp.position
                """).setParameter("b", b.getId()).getResultList()) {
            if (row[1] instanceof String url && url.startsWith("http") && urls.size() < MAX_REFERENCES) {
                urls.add(url);
                names.add((String) row[0]);
            }
        }
        if (urls.isEmpty()) {
            UUID category = b.getLinkCategoryId() != null ? b.getLinkCategoryId() : b.getCategoryId();
            String url = category == null ? null : first(em.createNativeQuery("""
                    SELECT i.url FROM product_image i
                    JOIN product p ON p.id = i.product_id AND p.is_published AND NOT p.deleted
                    JOIN product_category pc ON pc.product_id = p.id
                    JOIN category c ON c.id = pc.category_id
                    WHERE c.id = :c OR c.parent_id = :c
                    ORDER BY i.is_primary DESC, p.is_popular DESC, i.sort_order LIMIT 1
                    """).setParameter("c", category).getResultList());
            if (url != null) urls.add(url);
        }
        String theme = b.getOccasionId() == null ? null
                : occasions.findById(b.getOccasionId()).map(Occasion::getName).orElse(null);
        return new Job(b.getPlacement(), urls, names, p.getBrief(), theme, b.getName());
    }

    private static String first(List<?> rows) {
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
    }

    private void finish(UUID pictureId, String url, String failure) {
        if (failure != null) log.warn("Banner picture {} failed: {}", pictureId, failure);
        tx.executeWithoutResult(s -> pictures.findById(pictureId).ifPresent(p -> {
            p.setStatus(url != null ? PromoBannerPicture.Status.READY : PromoBannerPicture.Status.FAILED);
            p.setImageUrl(url);
            p.setNote(failure);
        }));
    }
}
