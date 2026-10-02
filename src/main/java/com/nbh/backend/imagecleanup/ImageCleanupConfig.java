package com.nbh.backend.imagecleanup;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini image-editing credentials and the Cloudinary server-side upload target. Image output has no free
 * tier: the Gemini key needs a Google Cloud project with billing enabled. Both base URLs are overridable so
 * the pipeline can be exercised against a local stand-in without real credentials.
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

    public boolean geminiConfigured() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }
}
