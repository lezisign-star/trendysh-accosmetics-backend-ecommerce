-- LPOs: suppliers and the purchase orders raised against them. Receiving one posts a real
-- GOODS_RECEIPT StockDocument through the existing single write path (StockPostingService) —
-- these tables never hold a stock quantity themselves, only ordered-vs-received bookkeeping.

CREATE TABLE supplier (
    id           UUID PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    contact_name VARCHAR(150),
    phone        VARCHAR(30),
    email        VARCHAR(255),
    address      VARCHAR(500),
    notes        VARCHAR(1000),
    deleted      BOOLEAN NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE purchase_order (
    id                 UUID PRIMARY KEY,
    po_number          VARCHAR(40) NOT NULL UNIQUE,
    supplier_id        UUID NOT NULL REFERENCES supplier(id),
    status             VARCHAR(20) NOT NULL
                       CHECK (status IN ('DRAFT','SENT','PARTIALLY_RECEIVED','RECEIVED','CANCELLED')),
    expected_date      DATE,
    notes              VARCHAR(1000),
    created_by_user_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_purchase_order_supplier ON purchase_order (supplier_id);
CREATE INDEX idx_purchase_order_status ON purchase_order (status);

CREATE TABLE purchase_order_line (
    id               UUID PRIMARY KEY,
    purchase_order_id UUID NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    line_no          INTEGER NOT NULL,
    variant_id       UUID NOT NULL REFERENCES product_variant(id),
    qty_ordered      NUMERIC(14,3) NOT NULL CHECK (qty_ordered > 0),
    qty_received     NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (qty_received >= 0),
    unit_cost        NUMERIC(14,2) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_purchase_order_line_po ON purchase_order_line (purchase_order_id);
CREATE INDEX idx_purchase_order_line_variant ON purchase_order_line (variant_id);
