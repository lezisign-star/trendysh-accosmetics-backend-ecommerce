-- Founder decision (2026-09-24): products belong to several categories, and a variant becomes
-- a plain name/photo/price/description row — no attribute/option concept in the schema for the
-- admin editor to model. This migration only ADDS: `product.category_id`/`subcategory_id` and
-- the attribute tables stay in place and readable until Phase 3f's cleanup migration (V28), once
-- the new frontend has fully replaced the old editor. See HANDOFF.md for the full reasoning and
-- the Phase 3.0 pre-flight (both dev and production have effectively no attribute data to lose).

-- ---------------------------------------------------------------------------
-- product_category: many-to-many. Only the category(ies) a product actually belongs to are
-- stored here (no automatic parent-implies-child row) — readers roll a subcategory up to its
-- parent in their own query, the same way the old category_id/subcategory_id pair worked.
-- `position` 0 is the "primary" category, used for breadcrumbs and the SKU prefix.
-- ---------------------------------------------------------------------------
CREATE TABLE product_category (
    product_id  UUID NOT NULL REFERENCES product(id),
    category_id UUID NOT NULL REFERENCES category(id),
    position    INT NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, category_id)
);

CREATE INDEX idx_product_category_category ON product_category (category_id);

-- Backfill: a product's existing subcategory (if any) is more specific than its category, so it
-- becomes the one stored row; otherwise its category does. A product with neither gets no row.
INSERT INTO product_category (product_id, category_id, position)
SELECT id, COALESCE(subcategory_id, category_id), 0
FROM product
WHERE COALESCE(subcategory_id, category_id) IS NOT NULL;

-- Guard: refuse to run if any product's subcategory disagrees with its category (the same
-- consistency ProductService.applyCategories already enforces on write — this only catches a
-- hand-edited row). The Phase 3.0 pre-flight found zero of these on dev and production.
DO $$
DECLARE
    bad_subcategory_parent INT;
BEGIN
    SELECT count(*) INTO bad_subcategory_parent
    FROM product p
    JOIN category sc ON sc.id = p.subcategory_id
    LEFT JOIN category c ON c.id = p.category_id
    WHERE p.subcategory_id IS NOT NULL AND (c.id IS NULL OR sc.parent_id IS DISTINCT FROM c.id);

    IF bad_subcategory_parent > 0 THEN
        RAISE EXCEPTION 'V27 refuses to run: % product(s) have a subcategory whose parent does not match their category. Fix these before migrating.', bad_subcategory_parent;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- product_variant: plain fields the new editor needs. `selling_price` becomes nullable so a
-- draft variant can be saved with no price at all (publish only WARNS about this, never blocks
-- a save) — ProductVariant.isSellable()/EffectivePriceResolver/VariantDealService all guard a
-- null price explicitly in Phase 3a's code change, this migration only widens the column.
-- ---------------------------------------------------------------------------
ALTER TABLE product_variant ADD COLUMN description TEXT;

ALTER TABLE product_variant ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

UPDATE product_variant v
SET sort_order = ranked.rn - 1
FROM (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY product_id ORDER BY created_at) AS rn
    FROM product_variant
) ranked
WHERE ranked.id = v.id;

ALTER TABLE product_variant ALTER COLUMN selling_price DROP NOT NULL;
