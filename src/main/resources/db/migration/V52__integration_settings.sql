-- Third-party credentials (Gemini key, Cloudinary account) managed from the admin dashboard instead of
-- server environment variables. Values are stored AES-GCM encrypted, never returned by the API.
CREATE TABLE integration_setting (
    setting_key VARCHAR(60) PRIMARY KEY,
    value_encrypted TEXT NOT NULL,
    updated_by_user_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
