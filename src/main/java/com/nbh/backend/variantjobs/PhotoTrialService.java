package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.imagecleanup.WatermarkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * "Try 2 first" (owner, 2026-10-15, after a whole job of gel polish photos came back wrong): staff pick one or two
 * colours, write how the photos must look and attach a real photo, and see those photos made before anything else is.
 * They change the words and try again until the photos are right; Generate (or Redo photos) then follows the same
 * words, and the approved photos are reused for their colours instead of being made again.
 *
 * <p>Also stores the real photo staff attach for the AI to follow (a "reference"): in the shop's own Cloudinary
 * folder, so the photo steps only ever download the shop's own files.
 */
@Slf4j
@Service
public class PhotoTrialService {

    public static final int MAX_TRIAL = 2;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    public record TrialColor(String name, String hex) {}

    public record TrialRequest(UUID productId, UUID baseVariantId, List<TrialColor> colors,
                               VariantJobRequest.PhotoGuide photoGuide, List<VariantJobRequest.PhotoGroup> photoGroups,
                               String prompt) {}

    /** {@code url} null and {@code error} set when that colour's photo couldn't be made. */
    public record TrialPhoto(String color, String url, String error) {}

    /** {@code original}: the photo the new ones were made from, to compare side by side. */
    public record TrialResult(String original, List<TrialPhoto> photos) {}

    private final ProductRepository products;
    private final VariantJobWriter writer;
    private final VariantPhotoMaker maker;
    private final GeminiImageClient gemini;
    private final CloudinaryUploader cloudinary;
    private final WatermarkService watermark;
    private final ImageCleanupConfig config;

    public PhotoTrialService(ProductRepository products, VariantJobWriter writer, VariantPhotoMaker maker,
                             GeminiImageClient gemini, CloudinaryUploader cloudinary, WatermarkService watermark,
                             ImageCleanupConfig config) {
        this.products = products;
        this.writer = writer;
        this.maker = maker;
        this.gemini = gemini;
        this.cloudinary = cloudinary;
        this.watermark = watermark;
        this.config = config;
    }

    /** Stores a real photo for the AI to follow; returns its address, to put in the photo instructions. */
    public String uploadReference(String image, String mimeType) {
        String mime = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(mime)) {
            throw new UnprocessableException("Use a JPEG, PNG or WebP picture.");
        }
        byte[] bytes = decode(image);
        if (bytes.length == 0) {
            throw new UnprocessableException("That picture is empty. Take or choose it again.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new UnprocessableException("That picture is too big (8 MB at most).");
        }
        if (!cloudinary.configured()) {
            throw new UnprocessableException("Cloudinary isn't set up yet — the photo can't be stored.");
        }
        // Not watermarked: it is never shown in the shop, only sent to the AI to look at.
        return cloudinary.upload(bytes, "products/references", "ref-" + UUID.randomUUID()).url();
    }

    /** The photo a job would start from (the base variant's main photo), so staff can mark the part that changes on it. */
    public String basePhotoUrl(java.util.UUID productId, java.util.UUID baseVariantId) {
        Product product = products.findById(productId).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such product."));
        ProductVariant base = writer.base(product.getId(), baseVariantId)
                .orElseThrow(() -> new NotFoundException("The product has no variant to start from."));
        VariantJobWriter.Source source = writer.basePhoto(product.getId(), base.getId());
        if (source == null) {
            throw new UnprocessableException("The product has no photo to start from. Add one first.");
        }
        return source.url();
    }

    public TrialResult trial(TrialRequest r) {
        if (r == null || r.productId() == null) {
            throw new UnprocessableException("Say which product the photos are for.");
        }
        List<TrialColor> colors = r.colors() == null ? List.of()
                : r.colors().stream().filter(c -> c != null && c.name() != null && !c.name().isBlank()).toList();
        if (colors.isEmpty()) {
            throw new UnprocessableException("Pick a colour to try.");
        }
        if (colors.size() > MAX_TRIAL) {
            throw new UnprocessableException("Try " + MAX_TRIAL + " colours at most at a time.");
        }
        if (!config.geminiConfigured()) {
            throw new UnprocessableException("The AI isn't set up yet — add the GEMINI_API_KEY before making photos.");
        }
        if (!cloudinary.configured()) {
            throw new UnprocessableException("Cloudinary isn't set up yet — the photos can't be stored.");
        }
        PhotoGuides guides = new PhotoGuides(r.photoGuide(), r.photoGroups(), null);
        guides.check(cloudinary::isOwnUrl);
        Product product = products.findById(r.productId()).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such product."));
        ProductVariant base = writer.base(product.getId(), r.baseVariantId())
                .orElseThrow(() -> new NotFoundException("The product has no variant to start from."));
        VariantJobWriter.Source source = writer.basePhoto(product.getId(), base.getId());
        if (source == null) {
            throw new UnprocessableException("The product has no photo to start from. Add one first.");
        }
        Map<String, GeminiImageClient.Image> downloaded = new ConcurrentHashMap<>();
        GeminiImageClient.Image start;
        try {
            start = maker.download(source.url(), downloaded);
        } catch (GeminiImageClient.GeminiException e) {
            throw new UnprocessableException("The product's photo couldn't be fetched: " + e.getMessage());
        }

        List<TrialPhoto> out = new ArrayList<>();
        boolean busy = false;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> calls = new ArrayList<>();
            for (TrialColor c : colors) {
                calls.add(pool.submit(() -> {
                    GeminiImageClient.Image made = maker.recolour(product.getName(), start, c.name().trim(),
                            c.hex(), r.prompt(), guides, downloaded);
                    String publicId = "trial-" + UUID.randomUUID().toString().substring(0, 12);
                    return cloudinary.upload(watermark.stamp(made.bytes()), "products", publicId).url();
                }));
            }
            for (int i = 0; i < calls.size(); i++) {
                String name = colors.get(i).name().trim();
                try {
                    out.add(new TrialPhoto(name, calls.get(i).get(), null));
                } catch (ExecutionException e) {
                    Throwable why = e.getCause();
                    if (why instanceof GeminiImageClient.GeminiException g && g.isQuotaExceeded()) {
                        busy = true;
                    }
                    log.info("Trial photo for {} failed: {}", name, why.getMessage());
                    out.add(new TrialPhoto(name, null, why instanceof GeminiImageClient.GeminiException g && g.isQuotaExceeded()
                            ? "The AI is busy at the moment. Try again in a few minutes."
                            : "Couldn't make this one: " + why.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UnprocessableException("That didn't finish. Try again.");
                }
            }
        }
        if (busy && out.stream().allMatch(p -> p.url() == null)) {
            throw new UnprocessableException("The AI is busy at the moment (its limit for now is used up). Try again in a few minutes.");
        }
        return new TrialResult(source.url(), out);
    }

    private static byte[] decode(String image) {
        if (image == null || image.isBlank()) {
            throw new UnprocessableException("There is no picture.");
        }
        String data = image.trim();
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma > 0) {
            data = data.substring(comma + 1);
        }
        try {
            return Base64.getMimeDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException("That isn't a picture. Take or choose it again.");
        }
    }
}
