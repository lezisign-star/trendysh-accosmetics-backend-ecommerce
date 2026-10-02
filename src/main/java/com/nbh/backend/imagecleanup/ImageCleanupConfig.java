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
    private String cloudinaryUploadBaseUrl = "https://api.cloudinary.com";
    /** Cleaned shots' studio backdrop. */
    private String backgroundHex = "#F3EBDD";
    private int maxImagesPerProduct = 6;

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
