-- A photo belongs to exactly ONE sellable option (a variant). The owner's rule (2026-10-07): "we must never have
-- shared photos among the related products". Before this, a photo filed at product level (variant_id NULL) was
-- shown on every option's page, and every photo of the product was shown on every option's page.
--
-- 1. On a product with ONE option, a product-level photo can only mean that option's own, so it is filed under it.
--    On a product with several options such a photo cannot be given to one of them by guesswork: it stays
--    product-level ("unassigned"), the shop stops showing it, and staff assign it in the editor.
UPDATE product_image pi
SET variant_id = sole.variant_id
FROM (
    SELECT product_id, (array_agg(id))[1] AS variant_id
    FROM product_variant
    WHERE deleted = false
    GROUP BY product_id
    HAVING count(*) = 1
) sole
WHERE pi.variant_id IS NULL
  AND pi.product_id = sole.product_id;

-- 2. Each owner now has its own main photo (a product used to have ONE main photo across all its options, so an
--    option's "main photo" quietly replaced the product's cover). Where one owner already holds two, keep the
--    earliest and clear the rest.
UPDATE product_image
SET is_primary = false
WHERE id IN (
    SELECT id FROM (
        SELECT id, row_number() OVER (PARTITION BY product_id, variant_id ORDER BY sort_order, created_at, id) AS rank_in_owner
        FROM product_image
        WHERE is_primary
    ) ranked
    WHERE rank_in_owner > 1
);

-- 3. ... and an owner that has photos but no main one gets its first.
UPDATE product_image
SET is_primary = true
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               row_number() OVER (PARTITION BY product_id, variant_id ORDER BY sort_order, created_at, id) AS rank_in_owner,
               bool_or(is_primary) OVER (PARTITION BY product_id, variant_id) AS has_main
        FROM product_image
    ) ranked
    WHERE rank_in_owner = 1 AND NOT has_main
);
