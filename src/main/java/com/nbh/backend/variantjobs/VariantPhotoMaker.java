package com.nbh.backend.variantjobs;

import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.variantgen.VariantPhotoPromptBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Makes one colour's photo from the starting photo, following staff's photo instructions ({@link PhotoGuides}): the
 * words for all colours, the colour's group's words, and a real photo of the range sent alongside. The trial in the
 * wizard and the job's photo step both use this, so what staff approve in a trial is what the job makes.
 */
@Component
public class VariantPhotoMaker {

    private final GeminiImageClient gemini;
    private final VariantPhotoPromptBuilder prompts;
    private final ImageCleanupConfig config;

    public VariantPhotoMaker(GeminiImageClient gemini, VariantPhotoPromptBuilder prompts, ImageCleanupConfig config) {
        this.gemini = gemini;
        this.prompts = prompts;
        this.config = config;
    }

    /**
     * @param downloaded photos already fetched in this run, by URL (a reference shared by many colours is fetched once)
     */
    public GeminiImageClient.Image recolour(String productName, GeminiImageClient.Image source, String color, String hex,
                                            String labelNote, PhotoGuides guides,
                                            Map<String, GeminiImageClient.Image> downloaded) {
        String referenceUrl = guides.referenceFor(color);
        List<GeminiImageClient.Image> extras = referenceUrl == null ? List.of()
                : List.of(download(referenceUrl, downloaded));
        String prompt = prompts.build(productName, color, hex, null, labelNote, config.getBackgroundHex(),
                guides.generalNote(), guides.groupNote(color), referenceUrl != null);
        GeminiImageClient.Image made = gemini.edit(source, extras, prompt);
        // Staff marked the part that may change: everything else is the product's own photo again, so the variant
        // differs from the main photo only there. Without a mark (or if the pictures do not line up) it is the AI's.
        String maskUrl = guides.maskFor(color);
        return maskUrl == null ? made : MaskComposer.apply(source, made, download(maskUrl, downloaded));
    }

    public GeminiImageClient.Image download(String url, Map<String, GeminiImageClient.Image> downloaded) {
        GeminiImageClient.Image image = downloaded.get(url);
        if (image == null) {
            image = gemini.download(url);
            downloaded.put(url, image);
        }
        return image;
    }
}
