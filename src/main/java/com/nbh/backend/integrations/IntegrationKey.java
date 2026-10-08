package com.nbh.backend.integrations;

/** The credentials that can be set from the admin dashboard. {@code secret} ones are never shown, only their last 4 characters. */
public enum IntegrationKey {
    GEMINI_API_KEY("Gemini API key", true),
    CLOUDINARY_CLOUD_NAME("Cloudinary cloud name", false),
    CLOUDINARY_API_KEY("Cloudinary API key", true),
    CLOUDINARY_API_SECRET("Cloudinary API secret", true),
    /** Render's deploy hook for the storefront: asking it to rebuild refreshes the pages search engines read. */
    STOREFRONT_DEPLOY_HOOK_URL("Storefront deploy hook", true);

    private final String label;
    private final boolean secret;

    IntegrationKey(String label, boolean secret) {
        this.label = label;
        this.secret = secret;
    }

    public String label() {
        return label;
    }

    public boolean secret() {
        return secret;
    }
}
