-- Phase 2: cart, checkout, orders, payments. Guest-only for now — there is no
-- customer account system yet (architecture doc §2.1 defers that; a cart
-- keys on session_id only, matching Moments Packaging's Cart shape which
-- treats guest carts as first-class).

CREATE TABLE cart (
    id          UUID PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL UNIQUE,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'CONVERTED', 'ABANDONED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cart_item (
    id                    UUID PRIMARY KEY,
    cart_id               UUID NOT NULL REFERENCES cart(id),
    variant_id            UUID NOT NULL REFERENCES product_variant(id),
    qty                   NUMERIC(14,3) NOT NULL CHECK (qty > 0),
    unit_price_snapshot   NUMERIC(14,2) NOT NULL,
    product_name_snapshot VARCHAR(250) NOT NULL,
    variant_name_snapshot VARCHAR(250) NOT NULL,
    added_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (cart_id, variant_id)
);

CREATE INDEX idx_cart_item_cart ON cart_item (cart_id);

-- No FK to a customer table — guest-only, per the note above. guest_email/
-- guest_phone identify the buyer; fulfilment_branch_id is required from day
-- one (architecture doc §4.7), currently always the seeded default branch
-- per the founder's confirmed decision (session 5).
CREATE TABLE customer_order (
    id                    UUID PRIMARY KEY,
    order_no              VARCHAR(40) NOT NULL UNIQUE,
    guest_email           VARCHAR(255) NOT NULL,
    guest_phone           VARCHAR(20) NOT NULL,
    fulfilment_branch_id  UUID NOT NULL REFERENCES branch(id),
    fulfilment_type       VARCHAR(20) NOT NULL
        CHECK (fulfilment_type IN ('DELIVERY_COURIER', 'DELIVERY_TUMABODA', 'BRANCH_PICKUP')),
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT'
        CHECK (status IN (
            'PENDING_PAYMENT', 'PAID', 'PICKING', 'READY', 'DISPATCHED', 'DELIVERED',
            'CANCELLED', 'PAYMENT_FAILED', 'EXPIRED'
        )),
    subtotal              NUMERIC(14,2) NOT NULL,
    vat_total             NUMERIC(14,2) NOT NULL,
    delivery_fee          NUMERIC(14,2) NOT NULL DEFAULT 0,
    grand_total           NUMERIC(14,2) NOT NULL,
    delivery_address      TEXT,
    placed_at             TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_order_status ON customer_order (status);
CREATE INDEX idx_customer_order_guest_email ON customer_order (guest_email);

CREATE TABLE customer_order_item (
    id                    UUID PRIMARY KEY,
    order_id              UUID NOT NULL REFERENCES customer_order(id),
    variant_id            UUID NOT NULL REFERENCES product_variant(id),
    qty                   NUMERIC(14,3) NOT NULL CHECK (qty > 0),
    unit_price            NUMERIC(14,2) NOT NULL,
    vat_rate              NUMERIC(5,4) NOT NULL,
    line_total            NUMERIC(14,2) NOT NULL,
    unit_cost_at_sale     NUMERIC(14,2),  -- filled in at fulfilment, from the stock ledger — margin capture
    reservation_id        UUID REFERENCES stock_reservation(id)
);

CREATE INDEX idx_customer_order_item_order ON customer_order_item (order_id);

-- Append-only attempt log — mirrors Moments' PaymentRecord: order state and
-- payment state are separate tables, every attempt is recorded, never
-- overwritten (architecture doc §1.1's "pattern to reuse").
CREATE TABLE payment_record (
    id                    UUID PRIMARY KEY,
    order_id              UUID NOT NULL REFERENCES customer_order(id),
    provider              VARCHAR(30) NOT NULL,
    provider_reference    VARCHAR(100),
    method                VARCHAR(20) NOT NULL CHECK (method IN ('MPESA', 'CARD', 'CASH', 'BANK')),
    amount                NUMERIC(14,2) NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'INITIATED'
        CHECK (status IN ('INITIATED', 'PENDING', 'SUCCESS', 'FAILED', 'REVERSED')),
    raw_request           JSONB,
    raw_response          JSONB,
    idempotency_key       VARCHAR(100) UNIQUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_record_order ON payment_record (order_id);
