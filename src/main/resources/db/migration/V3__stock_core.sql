-- The "voucher" — one document per business event that moves or holds stock.
CREATE TABLE stock_document (
    id                    UUID PRIMARY KEY,
    document_no           VARCHAR(40) NOT NULL UNIQUE,
    document_type         VARCHAR(20) NOT NULL CHECK (document_type IN (
        'GOODS_RECEIPT', 'SALE_ONLINE', 'SALE_POS', 'CUSTOMER_RETURN',
        'SUPPLIER_RETURN', 'TRANSFER', 'ADJUSTMENT', 'STOCK_COUNT', 'WRITE_OFF'
    )),
    status                VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    branch_id             UUID REFERENCES branch(id),
    posted_at             TIMESTAMPTZ,
    posted_by_user_id     UUID,
    reference_type        VARCHAR(40),
    reference_id          UUID,
    idempotency_key       VARCHAR(100) UNIQUE,
    notes                 VARCHAR(1000),
    cancelled_by_document_id UUID REFERENCES stock_document(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_document_reference ON stock_document (reference_type, reference_id);
CREATE INDEX idx_stock_document_branch ON stock_document (branch_id);

CREATE TABLE stock_document_line (
    id                  UUID PRIMARY KEY,
    document_id         UUID NOT NULL REFERENCES stock_document(id),
    line_no             INT NOT NULL,
    variant_id          UUID NOT NULL REFERENCES product_variant(id),
    source_location_id  UUID REFERENCES stock_location(id),
    target_location_id  UUID REFERENCES stock_location(id),
    qty                 NUMERIC(14,3) NOT NULL CHECK (qty > 0),
    unit_cost           NUMERIC(14,2),
    unit_price          NUMERIC(14,2),
    batch_no            VARCHAR(60),
    expiry_date         DATE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_line_has_a_side CHECK (source_location_id IS NOT NULL OR target_location_id IS NOT NULL),
    CONSTRAINT uq_document_line UNIQUE (document_id, line_no)
);

CREATE INDEX idx_stock_document_line_document ON stock_document_line (document_id);
CREATE INDEX idx_stock_document_line_variant ON stock_document_line (variant_id);

-- APPEND-ONLY. This table is the source of truth for every quantity that has
-- ever existed anywhere. No UPDATE, no DELETE, enforced below — a correction
-- is a reversal entry plus a new entry, never a mutation of history.
CREATE TABLE stock_ledger_entry (
    id                      BIGSERIAL PRIMARY KEY,
    variant_id              UUID NOT NULL REFERENCES product_variant(id),
    location_id             UUID NOT NULL REFERENCES stock_location(id),
    posted_at               TIMESTAMPTZ NOT NULL,
    qty_change              NUMERIC(14,3) NOT NULL,
    qty_after               NUMERIC(14,3) NOT NULL,
    unit_cost               NUMERIC(14,2),
    avg_cost_after           NUMERIC(14,2),
    stock_value_after       NUMERIC(14,2),
    stock_value_difference  NUMERIC(14,2),
    document_id             UUID NOT NULL REFERENCES stock_document(id),
    document_line_id        UUID NOT NULL REFERENCES stock_document_line(id),
    is_reversal             BOOLEAN NOT NULL DEFAULT false,
    reverses_entry_id       BIGINT REFERENCES stock_ledger_entry(id),
    created_by_user_id      UUID,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sle_variant_location_posted
    ON stock_ledger_entry (variant_id, location_id, posted_at DESC, id DESC);
CREATE INDEX idx_sle_document ON stock_ledger_entry (document_id);

CREATE OR REPLACE FUNCTION reject_stock_ledger_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'stock_ledger_entry is append-only: % is not permitted (id=%)', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_ledger_entry_no_update
    BEFORE UPDATE ON stock_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_stock_ledger_mutation();

CREATE TRIGGER trg_stock_ledger_entry_no_delete
    BEFORE DELETE ON stock_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_stock_ledger_mutation();

-- The derived, cached per-(variant, location) balance. Rebuildable at any time
-- from stock_ledger_entry alone (qty_after of the latest entry per pair) —
-- that reconciliation producing byte-identical results is this subsystem's
-- acceptance test.
CREATE TABLE stock_balance (
    id                     UUID PRIMARY KEY,
    variant_id             UUID NOT NULL REFERENCES product_variant(id),
    location_id            UUID NOT NULL REFERENCES stock_location(id),
    -- No CHECK(on_hand_qty >= 0) here: virtual locations (SUPPLIER/CUSTOMER/
    -- ADJUSTMENT/IN_TRANSIT) are DESIGNED to go negative — see StockPostingService's
    -- applyMovement, which enforces non-negativity only for is_internal locations.
    -- A blanket DB check would reject every goods receipt.
    on_hand_qty            NUMERIC(14,3) NOT NULL DEFAULT 0,
    reserved_qty           NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
    avg_cost               NUMERIC(14,2) NOT NULL DEFAULT 0,
    stock_value            NUMERIC(14,2) NOT NULL DEFAULT 0,
    last_ledger_entry_id   BIGINT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (variant_id, location_id)
);

-- Availability = on_hand_qty - reserved_qty (computed on read, never stored).
-- One hold per (cart/order/POS-ticket line). Both online checkout and POS
-- draw against the same table via the same source-discriminated arithmetic.
CREATE TABLE stock_reservation (
    id                UUID PRIMARY KEY,
    variant_id        UUID NOT NULL REFERENCES product_variant(id),
    location_id       UUID NOT NULL REFERENCES stock_location(id),
    qty               NUMERIC(14,3) NOT NULL CHECK (qty > 0),
    fulfilled_qty     NUMERIC(14,3) NOT NULL DEFAULT 0,
    released_qty      NUMERIC(14,3) NOT NULL DEFAULT 0,
    source            VARCHAR(20) NOT NULL CHECK (source IN (
        'ONLINE_CART', 'ONLINE_ORDER', 'POS_TICKET', 'TRANSFER_OUT', 'MANUAL_HOLD'
    )),
    reference_type    VARCHAR(40) NOT NULL,
    reference_id      UUID NOT NULL,
    status            VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN (
        'ACTIVE', 'FULFILLED', 'RELEASED', 'EXPIRED'
    )),
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID
);

CREATE INDEX idx_reservation_active_lookup
    ON stock_reservation (variant_id, location_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_reservation_reference ON stock_reservation (reference_type, reference_id);
