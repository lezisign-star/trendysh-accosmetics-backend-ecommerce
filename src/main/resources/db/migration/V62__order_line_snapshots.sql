-- An order line remembers what was bought. Until now a line pointed at the live variant and only froze the price, so
-- renaming a variant, changing its colour or size, or re-numbering its SKU quietly rewrote every old order (and the
-- emails sent from it). Every column here is nullable and additive: an order placed before this migration keeps working
-- through the live variant, and nothing that reads these columns needs them to be present.
--   product_name_snapshot / variant_name_snapshot / sku_snapshot: the names and SKU as the customer ordered them.
--   option_label_snapshot: the variant's stored short option ("Ruby Red / 50ml"), null when it had none. The short
--     option shown on screens is worked out from these frozen values, exactly as ProductVariant.shortOption() does.
--   option_color_snapshot / option_size_snapshot / swatch_hex_snapshot: the colour, the size and the colour's dot.
--   image_url_snapshot: the variant's own cover photo when it was ordered.
ALTER TABLE customer_order_item ADD COLUMN product_name_snapshot VARCHAR(250);
ALTER TABLE customer_order_item ADD COLUMN variant_name_snapshot VARCHAR(250);
ALTER TABLE customer_order_item ADD COLUMN sku_snapshot VARCHAR(60);
ALTER TABLE customer_order_item ADD COLUMN option_label_snapshot VARCHAR(160);
ALTER TABLE customer_order_item ADD COLUMN option_color_snapshot VARCHAR(80);
ALTER TABLE customer_order_item ADD COLUMN option_size_snapshot VARCHAR(80);
ALTER TABLE customer_order_item ADD COLUMN swatch_hex_snapshot VARCHAR(9);
ALTER TABLE customer_order_item ADD COLUMN image_url_snapshot VARCHAR(500);

-- Backfill: the best available for orders that already exist is the variant as it is now. A line whose variant was
-- renamed before this migration keeps today's name; there is no record of the older one.
UPDATE customer_order_item i
SET product_name_snapshot = p.name,
    variant_name_snapshot = v.variant_name,
    sku_snapshot          = v.sku,
    option_label_snapshot = v.option_label,
    option_color_snapshot = v.option_color,
    option_size_snapshot  = v.option_size,
    swatch_hex_snapshot   = v.swatch_hex
FROM product_variant v
JOIN product p ON p.id = v.product_id
WHERE v.id = i.variant_id;

-- The variant's own cover photo, by the same rule the shop uses (a photo belongs to one option; one filed at product
-- level counts only on a product with a single option): its main photo, else the first in gallery order.
UPDATE customer_order_item i
SET image_url_snapshot = (
    SELECT pi.url
    FROM product_image pi
    WHERE pi.product_id = v.product_id
      AND (pi.variant_id = v.id
           OR (pi.variant_id IS NULL
               AND (SELECT count(*) FROM product_variant s
                    WHERE s.product_id = v.product_id AND s.deleted = false) <= 1))
    ORDER BY pi.is_primary DESC, pi.sort_order ASC, pi.created_at ASC, pi.id ASC
    LIMIT 1)
FROM product_variant v
WHERE v.id = i.variant_id;
