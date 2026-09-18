-- Verified-purchase reviews: eligibility is checked against a real customer_order
-- (order number + the phone it was placed with), not a customer login — checkout
-- is guest-only. PENDING until a staff member moderates it.
CREATE TABLE product_review (
    id             UUID PRIMARY KEY,
    product_id     UUID NOT NULL REFERENCES product(id),
    order_id       UUID NOT NULL REFERENCES customer_order(id),
    rating         INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment        TEXT,
    reviewer_name  VARCHAR(150) NOT NULL,
    reviewer_phone VARCHAR(20) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    moderated_by   UUID,
    moderated_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One review per product per order — stops the same order being used to
    -- spam multiple reviews of the same product.
    UNIQUE (order_id, product_id)
);

CREATE INDEX idx_product_review_product_status ON product_review (product_id, status);

-- Append-only fact, never updated — same posture as customer_order_item
-- (see that entity's javadoc). One row per order line, written when payment
-- is CONFIRMED, not when the order is merely placed — an abandoned/unpaid
-- order must never count as a sale.
CREATE TABLE product_sale_event (
    id          UUID PRIMARY KEY,
    product_id  UUID NOT NULL REFERENCES product(id),
    variant_id  UUID NOT NULL REFERENCES product_variant(id),
    order_id    UUID NOT NULL REFERENCES customer_order(id),
    qty         NUMERIC(14,3) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_sale_event_product ON product_sale_event (product_id, occurred_at);
CREATE INDEX idx_product_sale_event_order ON product_sale_event (order_id);

-- Append-only fact — one row per storefront product-page view. variant_id is
-- nullable: a view can happen before any specific shade/size is chosen.
CREATE TABLE product_view_event (
    id          UUID PRIMARY KEY,
    product_id  UUID NOT NULL REFERENCES product(id),
    variant_id  UUID REFERENCES product_variant(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_view_event_product ON product_view_event (product_id, occurred_at);
