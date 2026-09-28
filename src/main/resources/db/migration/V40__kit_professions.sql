-- Kit professions (2026-09-28): a real Profession entity kits reference, replacing the free-text
-- profession_label/profession_slug pair each kit used to carry. That old shape put a UNIQUE
-- constraint on profession_slug, capping the catalogue at one kit per profession — the client wants
-- several kits per profession (e.g. a beginner and a pro Nail Tech kit), each with its own
-- description so a customer can tell them apart. See KitService's javadoc.
--
-- No backfill needed: production has zero kit rows (the client only ever ran V39 there), and the
-- 11 test kit rows this migration's author created locally while building phase 1 were deleted by
-- hand before this migration first ran locally.

CREATE TABLE profession (
    id         UUID PRIMARY KEY,
    slug       VARCHAR(100) NOT NULL UNIQUE,
    label      VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE kit DROP CONSTRAINT kit_profession_slug_key;
ALTER TABLE kit ADD COLUMN profession_id UUID NOT NULL REFERENCES profession(id);
ALTER TABLE kit ADD COLUMN description TEXT NOT NULL;
ALTER TABLE kit DROP COLUMN profession_slug;
ALTER TABLE kit DROP COLUMN profession_label;
CREATE INDEX idx_kit_profession ON kit (profession_id);
