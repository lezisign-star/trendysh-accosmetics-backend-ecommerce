-- AI-written product descriptions, drafted during a photo batch and held until a person approves them (owner,
-- 2026-10-08: "descriptions as part of a photo-cleaning batch"). Like the photo drafts, nothing in the shop reads this
-- table: approving a draft copies its text onto the product, rejecting it just deletes it.
CREATE TABLE product_description_draft (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID REFERENCES image_cleanup_batch(id),
    product_id UUID NOT NULL REFERENCES product(id),
    short_description VARCHAR(500),
    description TEXT,
    -- One highlight per line.
    highlights TEXT,
    ingredients TEXT,
    how_to_use TEXT,
    seo_title VARCHAR(200),
    seo_description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- One waiting draft per product: drafting again replaces it.
CREATE UNIQUE INDEX ux_product_description_draft_product ON product_description_draft (product_id);
CREATE INDEX ix_product_description_draft_batch ON product_description_draft (batch_id);

-- The product page's bullet list ("highlights") had nowhere to be stored: it was always empty. One per line.
ALTER TABLE product ADD COLUMN highlights TEXT;

-- Whether a run also drafts descriptions, and how many it drafted.
ALTER TABLE image_cleanup_batch ADD COLUMN write_descriptions BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE image_cleanup_batch ADD COLUMN descriptions_drafted INTEGER NOT NULL DEFAULT 0;
