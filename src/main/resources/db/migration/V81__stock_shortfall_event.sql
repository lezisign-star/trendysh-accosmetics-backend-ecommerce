-- "A customer could not order" (owner, 2026-10-28): each time the shop refuses a shopper for stock (adding to the cart,
-- raising a quantity, or placing the order), it is written down here and shown to staff as a critical alert that stays
-- until a person marks it handled. It also lists, on the Restock screen, who wanted what.
--  * variant_id is not a foreign key on purpose: the row is written in its own transaction while the shopper's request
--    is being refused, and must never wait on (or lock) the product rows that request touched.
--  * session_ref is the shopper's cart session, so one shopper trying again and again is one event, not ten.
--  * contact_* are only known at checkout (the details the shopper typed); a cart refusal has none.
CREATE TABLE IF NOT EXISTS stock_shortfall_event (
    id              UUID PRIMARY KEY,
    variant_id      UUID NOT NULL,
    product_name    VARCHAR(300) NOT NULL,
    qty_asked       NUMERIC(14, 3) NOT NULL,
    qty_left        NUMERIC(14, 3) NOT NULL,
    source          VARCHAR(20) NOT NULL CHECK (source IN ('CART', 'CHECKOUT')),
    session_ref     VARCHAR(100),
    contact_name    VARCHAR(200),
    contact_phone   VARCHAR(30),
    contact_email   VARCHAR(200),
    handled_at      TIMESTAMPTZ,
    handled_by      VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_stock_shortfall_unhandled ON stock_shortfall_event (created_at) WHERE handled_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_stock_shortfall_variant ON stock_shortfall_event (variant_id, created_at);
