-- A range's configuration (owner, 2026-10-17): a product with several variants is the configuration they come from,
-- not a product of its own. It holds what every variant shares (the common name, price, size, description, category,
-- the group photo) and switches saying which of these may differ per variant. Variants keep their stored price, size,
-- name and description, so the shop, cart and checkout read exactly what they read before; saving the configuration
-- writes the shared values into the variants. Nothing changes for a range until staff confirm its configuration
-- (range_reviewed_at). Columns only: no existing data is rewritten.
ALTER TABLE product ADD COLUMN shared_price NUMERIC(14, 2);
ALTER TABLE product ADD COLUMN shared_size VARCHAR(80);
ALTER TABLE product ADD COLUMN price_varies BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product ADD COLUMN size_varies BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product ADD COLUMN description_varies BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product ADD COLUMN category_varies BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product ADD COLUMN range_reviewed_at TIMESTAMPTZ;
-- The photo of all the variants together: read for the colours, kept with the configuration, never shown in the shop.
ALTER TABLE product ADD COLUMN group_photo_url TEXT;

-- The variant's own part of its name ("Bubblegum Pink RE106"); its full name is the configuration's common name, a
-- dash, and this.
ALTER TABLE product_variant ADD COLUMN name_part VARCHAR(200);
-- A variant filed under another category than its configuration's (only used when the range lets categories differ).
ALTER TABLE product_variant ADD COLUMN category_id UUID REFERENCES category (id);
CREATE INDEX idx_product_variant_category ON product_variant (category_id) WHERE category_id IS NOT NULL;
