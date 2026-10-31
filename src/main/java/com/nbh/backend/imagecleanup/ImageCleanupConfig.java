package com.nbh.backend.imagecleanup;

import com.nbh.backend.integrations.IntegrationKey;
import com.nbh.backend.integrations.IntegrationSettingsService;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Tunables for Gemini image editing. The API key itself comes from {@link IntegrationSettingsService} (saved in
 * the admin dashboard, else the GEMINI_API_KEY environment variable). Image output has no free tier: the key
 * needs a Google Cloud project with billing enabled. Both base URLs are overridable so the pipeline can be
 * exercised against a local stand-in without real credentials.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.image-cleanup")
public class ImageCleanupConfig {
    private String geminiApiKey = "";
    private String geminiBaseUrl = "https://generativelanguage.googleapis.com";
    private String geminiModel = "gemini-2.5-flash-image";
    /**
     * The text model that writes product descriptions (same API key as the photo editing). gemini-2.5-flash was
     * closed to new users (404 "no longer available", 2026-10-10); Google named gemini-3.8-flash as its replacement.
     * Overridable with GEMINI_DESCRIPTION_MODEL so the next retirement needs a setting, not a release.
     */
    private String descriptionModel = "gemini-3.8-flash";
    /**
     * How hard the text model thinks before answering (Gemini's thinkingLevel). Left to itself a 3.x flash model with
     * a photo and a fixed answer shape often took over a minute and timed out (descriptions and banner briefs, both
     * 2026-10-30); "low" keeps it to seconds. Blank sends no thinking setting, for a model that refuses one.
     */
    private String descriptionThinkingLevel = "low";
    private String cloudinaryUploadBaseUrl = "https://api.cloudinary.com";
    /** Cleaned shots' studio backdrop. */
    private String backgroundHex = "#F3EBDD";
    /** The most photos one run may ask for per option: one per view staff can tick. */
    private int maxImagesPerProduct = PhotoView.values().length;

    private IntegrationSettingsService settings;

    @Autowired
    public void bind(IntegrationSettingsService settings) {
        this.settings = settings;
    }

    /** The key currently in force: the dashboard value, else the environment variable. */
    public String effectiveGeminiKey() {
        return settings != null ? settings.get(IntegrationKey.GEMINI_API_KEY) : geminiApiKey;
    }

    public boolean geminiConfigured() {
        String key = effectiveGeminiKey();
        return key != null && !key.isBlank();
    }
}
