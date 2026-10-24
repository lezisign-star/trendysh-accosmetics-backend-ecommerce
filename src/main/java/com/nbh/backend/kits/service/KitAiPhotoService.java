package com.nbh.backend.kits.service;

import com.nbh.backend.catalog.dto.AdminImageDto;
import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.service.ProductService;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException;
import com.nbh.backend.imagecleanup.GeminiImageClient.Image;
import com.nbh.backend.kits.service.KitService.AiPhotoInputs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A kit's picture made with Gemini from the photos of the products inside it (owner, 2026-10-23: "generated using
 * integrated gemini from the images of products in it such that its thumbnail best suits the profession or use or the
 * image of the combination of the tools"). Two kinds: the products together as one set ({@link Style#SET}), or a
 * professional of the kit's trade at work with them ({@link Style#AT_WORK}). The picture is filed with the kit's own
 * photos; it becomes the thumbnail only when the kit has none yet, otherwise staff choose it (as with any photo).
 * Nothing is held open while Gemini works: the inputs are read first, the picture is filed after.
 */
@Slf4j
@Service
public class KitAiPhotoService {

    public enum Style { SET, AT_WORK }

    /** Enough for Gemini to see every product of a usual kit without the request growing past what it handles well. */
    static final int MAX_PHOTOS = 6;

    private final KitService kits;
    private final GeminiImageClient gemini;
    private final CloudinaryUploader cloudinary;
    private final ProductService products;

    public KitAiPhotoService(KitService kits, GeminiImageClient gemini, CloudinaryUploader cloudinary,
                             ProductService products) {
        this.kits = kits;
        this.gemini = gemini;
        this.cloudinary = cloudinary;
        this.products = products;
    }

    public AdminImageDto make(UUID kitId, Style style, String note) {
        AiPhotoInputs in = kits.aiPhotoInputs(kitId);
        if (in.photoUrls().isEmpty()) {
            throw new UnprocessableException("None of the products in this kit has a photo yet, so there is nothing to make"
                    + " its picture from. Add the products' photos, or take a photo of the kit.");
        }
        if (!cloudinary.configured()) {
            throw new UnprocessableException("Photo storage (Cloudinary) is not set up, so a picture can't be kept.");
        }
        int count = Math.min(MAX_PHOTOS, in.photoUrls().size());
        Image picture;
        try {
            List<Image> photos = new ArrayList<>();
            for (String url : in.photoUrls().subList(0, count)) {
                photos.add(gemini.download(url));
            }
            picture = gemini.edit(photos.get(0), photos.subList(1, photos.size()),
                    prompt(style, in, count, note));
        } catch (GeminiException e) {
            log.warn("Kit {} picture: {}", kitId, e.getMessage());
            throw new UnprocessableException(e.isQuotaExceeded()
                    ? "Gemini's limit for now is used up. Try again later, or take a photo of the kit."
                    : "Gemini could not make the picture this time. Try again, or take a photo of the kit. ("
                            + e.getMessage() + ")");
        }
        String publicId = "kit-" + in.variantId() + "-" + System.currentTimeMillis();
        String url = cloudinary.upload(picture.bytes(), "kits", publicId).url();
        String alt = style == Style.AT_WORK
                ? in.professionLabel() + " at work with the " + in.kitName()
                : "What is in the " + in.kitName();
        return products.attachImage(in.productId(),
                new AttachImageRequest(url, null, in.variantId(), alt, false, null, null, null));
    }

    /** What Gemini is asked: the photos are the real products, so they must look like themselves. */
    static String prompt(Style style, AiPhotoInputs in, int count, String note) {
        String products = String.join(", ", in.productNames().subList(0, count));
        String common = "The " + count + " photos are the real products of a beauty-supply kit called \"" + in.kitName()
                + "\" for a " + in.professionLabel() + " (" + products + "). Keep every product exactly as it looks in its"
                + " photo: the same shape, colours, packaging and labels; do not invent products, brands or text. No"
                + " words, logos, prices or watermarks in the picture. Portrait, 4:5, sharp, warm natural light, the kind"
                + " of photo a Nairobi beauty-supply shop would put on its website.";
        String scene = style == Style.AT_WORK
                ? " Make a new photo of a Kenyan " + in.professionLabel().toLowerCase(java.util.Locale.ROOT)
                        + " at work in a clean, modern salon, using these products, which are clearly visible on the"
                        + " workstation beside them. The person's face and hands look natural and real; tasteful and"
                        + " professional."
                : " Make a new photo of these products together as one neat set, arranged on a clean, light surface"
                        + " with a soft shadow, every product fully in view and none cut off, as a product photo of the"
                        + " whole kit.";
        String extra = note == null || note.isBlank() ? "" : " Also: " + note.trim();
        return common + scene + extra;
    }
}
