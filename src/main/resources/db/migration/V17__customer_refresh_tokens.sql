-- Long-lived shopper sign-in. The browser holds only an opaque random value in an
-- httpOnly cookie; we store its SHA-256 hash. Every use rotates it (old one revoked,
-- new one issued in the same family), so a replayed old token means theft and the
-- whole family is revoked.
CREATE TABLE customer_refresh_token (
    id          UUID PRIMARY KEY,
    account_id  UUID NOT NULL REFERENCES customer_account(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    family_id   UUID NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    -- Set when a refresh swaps this token for the next one (normal use).
    rotated_at  TIMESTAMPTZ,
    -- Set on logout, password change, or detected theft. Never usable again, no grace.
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_refresh_token_account ON customer_refresh_token (account_id);
CREATE INDEX idx_customer_refresh_token_family ON customer_refresh_token (family_id);
