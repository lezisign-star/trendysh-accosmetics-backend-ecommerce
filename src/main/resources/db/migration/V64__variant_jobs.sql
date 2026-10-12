-- Variants v2, phase 3: variant generation as background jobs that run side by side, survive a restart and pause on the
-- AI quota (VARIANTS_PLAN.md 3.3). All additive; the old variant generator (image_cleanup_batch rows) is untouched.
--   variant_job: one "Generate" from the wizard. request = the checked wizard body as JSON text, kept so a retry or a
--     restart works from exactly what staff confirmed. stage = how far it got (CREATE, DESCRIPTIONS, PHOTOS, DONE).
--     locked_by / heartbeat_at: which server is working on it and when it last said so; a RUNNING job whose heartbeat is
--     old is taken over (the server restarted mid-job). next_attempt_at: when a job paused on the quota tries again.
--   variant_job_item: one variant the job makes, with its own price, opening stock and how its description and photo went.
CREATE TABLE variant_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES product(id),
    status VARCHAR(24) NOT NULL,
    stage VARCHAR(16) NOT NULL DEFAULT 'CREATE',
    approve_first BOOLEAN NOT NULL DEFAULT false,
    request TEXT NOT NULL,
    summary TEXT,
    created_by UUID REFERENCES staff_user(id),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    locked_by VARCHAR(80),
    heartbeat_at TIMESTAMPTZ,
    cancel_requested BOOLEAN NOT NULL DEFAULT false,
    last_error TEXT,
    variants_total INTEGER NOT NULL DEFAULT 0,
    variants_created INTEGER NOT NULL DEFAULT 0,
    descriptions_done INTEGER NOT NULL DEFAULT 0,
    descriptions_failed INTEGER NOT NULL DEFAULT 0,
    photos_done INTEGER NOT NULL DEFAULT 0,
    photos_failed INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_variant_job_status ON variant_job (status, created_at);
CREATE INDEX ix_variant_job_product ON variant_job (product_id);

CREATE TABLE variant_job_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES variant_job(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    variant_id UUID REFERENCES product_variant(id),
    color VARCHAR(80),
    size VARCHAR(80),
    price NUMERIC(14, 2) NOT NULL,
    opening_stock NUMERIC(14, 3),
    unit_cost NUMERIC(14, 4),
    description_status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    photo_status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    photo_attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_variant_job_item_job ON variant_job_item (job_id, position);
CREATE INDEX ix_variant_job_item_variant ON variant_job_item (variant_id);
