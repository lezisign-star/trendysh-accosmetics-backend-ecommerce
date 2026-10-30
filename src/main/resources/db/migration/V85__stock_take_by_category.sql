-- A stock take, category by category (owner, 30 Oct 2026): one session for the shop; each top-level category is its own
-- count, taken by one person or several, applied as soon as it is finished. See StockTakeService.
CREATE TABLE IF NOT EXISTS stock_take (
    id UUID PRIMARY KEY,
    take_no VARCHAR(40) NOT NULL UNIQUE,
    -- OPEN while categories are being counted; CLOSED when staff end it.
    status VARCHAR(10) NOT NULL,
    created_by_user_id UUID,
    created_by_name VARCHAR(200),
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Which stock take and category a count belongs to, and who has counted in it ("Mary, John").
ALTER TABLE stock_count ADD COLUMN IF NOT EXISTS take_id UUID REFERENCES stock_take(id);
ALTER TABLE stock_count ADD COLUMN IF NOT EXISTS category_id UUID REFERENCES category(id);
ALTER TABLE stock_count ADD COLUMN IF NOT EXISTS counted_by_names VARCHAR(500);
CREATE INDEX IF NOT EXISTS ix_stock_count_take ON stock_count (take_id, category_id);

-- "Skip": the counter looked and chose to leave this item as it was (it is not changed when the count is applied).
ALTER TABLE stock_count_line ADD COLUMN IF NOT EXISTS skipped BOOLEAN NOT NULL DEFAULT FALSE;
