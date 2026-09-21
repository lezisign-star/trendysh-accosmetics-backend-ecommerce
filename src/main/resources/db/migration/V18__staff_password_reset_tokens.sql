-- One-time password-reset links for staff (mirrors customer_email_token's RESET_PASSWORD purpose).
-- Only a SHA-256 hash of the token is stored, so a database leak can't be replayed.
CREATE TABLE staff_password_reset_token (
    id          UUID PRIMARY KEY,
    staff_id    UUID NOT NULL REFERENCES staff_user(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_staff_password_reset_token_staff ON staff_password_reset_token (staff_id, created_at DESC);
