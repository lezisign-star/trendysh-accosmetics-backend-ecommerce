-- Inventory redesign Phase 2 (2026-09-27): stock counts — the one core inventory workflow this
-- system genuinely had no path for at all (a plain "set to N" existed via VariantStockService,
-- but nothing captured a real count session: a frozen snapshot to compare against, who counted
-- what, or a conflict when stock moved between the count and applying it).
CREATE TABLE stock_count (
    id UUID PRIMARY KEY,
    count_no VARCHAR(40) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    blind BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(500),
    assigned_to_user_id UUID,
    created_by_user_id UUID NOT NULL,
    applied_by_user_id UUID,
    applied_at TIMESTAMPTZ,
    applied_document_id UUID REFERENCES stock_document(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE stock_count_line (
    id UUID PRIMARY KEY,
    stock_count_id UUID NOT NULL REFERENCES stock_count(id),
    variant_id UUID NOT NULL REFERENCES product_variant(id),
    -- Frozen when the line is added — the apply-time comparison against the THEN-current
    -- on-hand is what detects "stock moved since this count was taken", not a live re-read.
    system_qty_at_start NUMERIC(14,3) NOT NULL,
    counted_qty NUMERIC(14,3),
    counted_by_user_id UUID,
    counted_at TIMESTAMPTZ,
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (stock_count_id, variant_id)
);

CREATE INDEX idx_stock_count_line_count ON stock_count_line(stock_count_id);
