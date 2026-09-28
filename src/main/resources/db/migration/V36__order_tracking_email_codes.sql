-- Guest order lookup by email (2026-09-28). An order number on its own now shows only a status;
-- seeing the orders under an email needs proof of the mailbox: a 6-digit code emailed to it, then a
-- short-lived session. Only SHA-256 hashes are stored, never the code or the session token.

CREATE TABLE order_tracking_code (
    id          UUID PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    code_hash   VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_tracking_code_email ON order_tracking_code (email, created_at);

CREATE TABLE order_tracking_session (
    id          UUID PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
