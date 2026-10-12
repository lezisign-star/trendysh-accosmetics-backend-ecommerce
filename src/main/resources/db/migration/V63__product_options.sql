-- Variants v2, phase 2: a product's own list of colours and sizes (VARIANTS_PLAN.md 3.1). All additive.
--   product_option_value: the colours and sizes a product comes in, in the order the shop shows them. The variant picker
--     reads it, so the order stays right even when some combinations are sold out or not made. A variant still carries
--     its own copy of its colour and size (option_color / option_size, V61), which is what cards and orders read.
--   swatch_hex / color_family (COLOR rows): the dot, and staff's choice of family when the one worked out from the dot
--     is wrong. Null color_family = "work it out from the dot" (ColorFamily.fromHex), so nothing here needs backfilling.
--   size_label_text / size_photo_image_id (SIZE rows): what that size's packaging says and a photo of that size, used by
--     the variant generator (phase 3). Nothing fills them yet.
CREATE TABLE product_option_value (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES product(id),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('COLOR', 'SIZE')),
    value VARCHAR(80) NOT NULL,
    position INTEGER NOT NULL,
    swatch_hex VARCHAR(9),
    color_family VARCHAR(20),
    size_label_text VARCHAR(60),
    size_photo_image_id UUID REFERENCES product_image(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- "Red" and "red " are the same colour.
CREATE UNIQUE INDEX ux_product_option_value ON product_option_value (product_id, kind, lower(value));

-- Staff's choice of colour family for this variant (null = work it out from swatch_hex), and where its size sits in the
-- product's size order (null = no size), so a card can be sorted and filtered without reading the option rows.
ALTER TABLE product_variant ADD COLUMN color_family VARCHAR(20);
ALTER TABLE product_variant ADD COLUMN size_position INTEGER;

-- Backfill from the variants that already have a colour or a size (the generated ones): one row per distinct value
-- (any case, trimmed), spelled and ordered as the product's first variant with it, dot from the first variant that has
-- one. Removed variants are ignored.
INSERT INTO product_option_value (product_id, kind, value, position, swatch_hex)
SELECT product_id, 'COLOR', value,
       (ROW_NUMBER() OVER (PARTITION BY product_id ORDER BY first_order, first_id)) - 1,
       (SELECT v2.swatch_hex FROM product_variant v2
         WHERE v2.product_id = x.product_id AND v2.deleted = false AND v2.swatch_hex IS NOT NULL
           AND lower(btrim(v2.option_color)) = x.value_key
         ORDER BY v2.sort_order, v2.id LIMIT 1)
FROM (
    SELECT DISTINCT ON (v.product_id, lower(btrim(v.option_color)))
           v.product_id, lower(btrim(v.option_color)) AS value_key, btrim(v.option_color) AS value,
           v.sort_order AS first_order, v.id AS first_id
    FROM product_variant v
    WHERE v.deleted = false AND v.option_color IS NOT NULL AND btrim(v.option_color) <> ''
    ORDER BY v.product_id, lower(btrim(v.option_color)), v.sort_order, v.id
) x;

INSERT INTO product_option_value (product_id, kind, value, position)
SELECT product_id, 'SIZE', value,
       (ROW_NUMBER() OVER (PARTITION BY product_id ORDER BY first_order, first_id)) - 1
FROM (
    SELECT DISTINCT ON (v.product_id, lower(btrim(v.option_size)))
           v.product_id, lower(btrim(v.option_size)) AS value_key, btrim(v.option_size) AS value,
           v.sort_order AS first_order, v.id AS first_id
    FROM product_variant v
    WHERE v.deleted = false AND v.option_size IS NOT NULL AND btrim(v.option_size) <> ''
    ORDER BY v.product_id, lower(btrim(v.option_size)), v.sort_order, v.id
) x;

UPDATE product_variant v
SET size_position = o.position
FROM product_option_value o
WHERE o.product_id = v.product_id AND o.kind = 'SIZE' AND lower(o.value) = lower(btrim(v.option_size))
  AND v.deleted = false;
