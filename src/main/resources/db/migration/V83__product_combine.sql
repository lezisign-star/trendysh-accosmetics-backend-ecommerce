-- Combining products into one (owner, 29 Oct 2026): products staff created once per colour or size are joined under
-- one main product, whose configuration then holds every setting. Each combine is written down with what it changed,
-- so it can be split back exactly. See ProductCombineService.
CREATE TABLE IF NOT EXISTS product_combine (
    id UUID PRIMARY KEY,
    main_product_id UUID NOT NULL REFERENCES product(id),
    combined_by VARCHAR(200),
    -- What the main and each joined product were before (ProductCombineService.Snapshot, as JSON).
    snapshot TEXT NOT NULL,
    joined_names TEXT NOT NULL,
    split_at TIMESTAMPTZ,
    split_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_product_combine_main ON product_combine (main_product_id, created_at DESC);
