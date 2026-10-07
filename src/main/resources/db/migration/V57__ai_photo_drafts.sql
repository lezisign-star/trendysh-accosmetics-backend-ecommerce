-- AI photos can now wait for a person's approval before they reach the shop (owner, 2026-10-07: "by default, they
-- should preview first before they go live"). A draft is a generated photo nobody in the shop can see yet; approving
-- it creates the ordinary product_image row, rejecting it (or letting it expire) deletes it and its Cloudinary file.
CREATE TABLE image_draft (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES image_cleanup_batch(id),
    product_id UUID NOT NULL REFERENCES product(id),
    -- A photo belongs to exactly one option, so a draft is made FOR one option and lands under it.
    variant_id UUID NOT NULL REFERENCES product_variant(id),
    -- The real photo it was made from (no foreign key: staff may remove that photo while the draft waits).
    reference_image_id UUID NOT NULL,
    view_key VARCHAR(30) NOT NULL,
    note VARCHAR(600),
    url VARCHAR(500) NOT NULL,
    cloudinary_public_id VARCHAR(255),
    prompt_used TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_image_draft_product ON image_draft (product_id);
CREATE INDEX ix_image_draft_variant ON image_draft (variant_id);
CREATE INDEX ix_image_draft_batch ON image_draft (batch_id);

-- How a run publishes: IMMEDIATE puts the new photos straight into the shop (how every run worked before, so the
-- existing batches are IMMEDIATE); REVIEW holds them as drafts. Which views were asked for, and the staff note, are
-- kept so a run can be understood later.
ALTER TABLE image_cleanup_batch ADD COLUMN publish_mode VARCHAR(10) NOT NULL DEFAULT 'IMMEDIATE';
ALTER TABLE image_cleanup_batch ADD CONSTRAINT image_cleanup_batch_publish_mode_check CHECK (publish_mode IN ('IMMEDIATE', 'REVIEW'));
ALTER TABLE image_cleanup_batch ADD COLUMN views VARCHAR(300);
ALTER TABLE image_cleanup_batch ADD COLUMN note VARCHAR(600);
