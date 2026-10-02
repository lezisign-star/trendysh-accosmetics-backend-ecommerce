-- A developer-only on/off control for Co-op Bank automatic confirmation (the INS webhook
-- receiver already exists and self-disables safely without real credentials — this is purely
-- the admin-facing visibility switch, defaulting off).
ALTER TABLE payment_settings
    ADD COLUMN coop_bank_enabled BOOLEAN NOT NULL DEFAULT false;
