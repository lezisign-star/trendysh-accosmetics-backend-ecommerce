-- Owner, 2026-10-24.
--
-- 1. Professions get what a trade's own page needs: a short description and a picture. A kit keeps its main trade
--    (kit.profession_id: its link, breadcrumb and admin filter) and can also be listed under other trades
--    (kit_extra_profession), so a kit that suits nail techs and pedicurists is not duplicated.
-- 2. Deal pictures: a promotional photo made with Gemini from a product's real photo while it is on a flash deal. Kept
--    apart from product_image on purpose, so they never show in a product's own gallery; staff approve one before the
--    shop shows it on deal cards. FAILED records an attempt that did not work, so it is not retried on its own.

ALTER TABLE profession ADD COLUMN description TEXT;
ALTER TABLE profession ADD COLUMN image_url TEXT;

CREATE TABLE kit_extra_profession (
    kit_id        UUID NOT NULL REFERENCES kit(id) ON DELETE CASCADE,
    profession_id UUID NOT NULL REFERENCES profession(id),
    PRIMARY KEY (kit_id, profession_id)
);
CREATE INDEX idx_kit_extra_profession_profession ON kit_extra_profession (profession_id);

CREATE TABLE deal_photo (
    id          UUID PRIMARY KEY,
    variant_id  UUID NOT NULL REFERENCES product_variant(id),
    url         TEXT,
    status      VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'FAILED')),
    note        TEXT,
    decided_by  VARCHAR(255),
    decided_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_deal_photo_variant ON deal_photo (variant_id, status);
CREATE INDEX idx_deal_photo_status ON deal_photo (status, created_at);
