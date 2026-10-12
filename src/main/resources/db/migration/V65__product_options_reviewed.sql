-- Variants v2, phase 6: a product whose variants staff have looked at in "Tidy colours & sizes" (given colours/sizes,
-- or left as they are because they are not colours or sizes, e.g. "Pack of 3"). Null = not looked at yet; such a
-- product with several variants lacking a colour or size is listed there. Additive.
ALTER TABLE product ADD COLUMN options_reviewed_at TIMESTAMPTZ;
