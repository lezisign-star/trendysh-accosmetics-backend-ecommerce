-- Customer accounts (backlog item 7). Checkout stays possible without one
-- (guest orders keep working); an account adds sign-in, "my orders", and
-- prefilled details. Customers are a separate table from staff_user on purpose:
-- different fields, no roles/permissions, and a customer email must never be
-- able to collide with — or be mistaken for — a staff login.
CREATE TABLE customer_account (
    id             UUID PRIMARY KEY,
    email          VARCHAR(255) NOT NULL,
    password       VARCHAR(255) NOT NULL,
    full_name      VARCHAR(200) NOT NULL,
    phone          VARCHAR(20),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Emails are stored lower-cased by the application; the index makes that a hard rule.
CREATE UNIQUE INDEX uq_customer_account_email ON customer_account (lower(email));

-- One-time links for verifying an email address and resetting a password. Only a
-- SHA-256 hash of the token is stored, so a database leak can't be replayed.
CREATE TABLE customer_email_token (
    id          UUID PRIMARY KEY,
    account_id  UUID NOT NULL REFERENCES customer_account(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    purpose     VARCHAR(20) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_email_token_account ON customer_email_token (account_id, purpose, created_at DESC);

-- Nullable: guest orders have no account. Set for orders placed while signed in,
-- and for earlier guest orders once the account's email has been verified.
ALTER TABLE customer_order ADD COLUMN customer_account_id UUID REFERENCES customer_account(id);
CREATE INDEX idx_customer_order_account ON customer_order (customer_account_id, created_at DESC);
