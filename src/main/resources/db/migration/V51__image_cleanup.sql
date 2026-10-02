-- AI product-photo cleanup (Gemini image editing): batches, and the images each one produced so a whole
-- batch can be undone. Cleaned images are ordinary product_image rows with source AI_CLEANED.
ALTER TABLE product_image DROP CONSTRAINT IF EXISTS product_image_source_check;
ALTER TABLE product_image
    ADD CONSTRAINT product_image_source_check CHECK (source IN ('UPLOAD', 'CAMERA_CAPTURE', 'AI_CLEANED'));

CREATE TABLE image_cleanup_batch (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status VARCHAR(30) NOT NULL,
    images_per_product INT NOT NULL,
    requested_count INT NOT NULL DEFAULT 0,
    succeeded_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(600),
    triggered_by_user_id UUID,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE image_cleanup_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES image_cleanup_batch(id),
    product_id UUID NOT NULL REFERENCES product(id),
    product_image_id UUID NOT NULL,
    reference_image_id UUID NOT NULL,
    cloudinary_public_id VARCHAR(255),
    prompt_used TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_image_cleanup_item_batch ON image_cleanup_item (batch_id);
