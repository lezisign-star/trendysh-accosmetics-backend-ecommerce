-- Variants generated from a finished product (staff answer a few questions, the AI plans, names, describes and
-- photographs each colour/size). All additive.
--   option_label / option_color / option_size: the short option ("Emerald Green / Large") the variant picker shows, since
--     variant_name for a generated variant carries the full product title ("JIO Pot Set - Emerald Green").
--   swatch_hex: the picker's colour dot, chosen from the colour's name (staff can change it).
--   generated_from_variant_id: the variant whose photo and details it was made from.
--   generation_batch_id: the run that made it; approve-first runs create these hidden until staff publish the run.
ALTER TABLE product_variant ADD COLUMN swatch_hex VARCHAR(9);
ALTER TABLE product_variant ADD COLUMN option_label VARCHAR(160);
ALTER TABLE product_variant ADD COLUMN option_color VARCHAR(80);
ALTER TABLE product_variant ADD COLUMN option_size VARCHAR(80);
ALTER TABLE product_variant ADD COLUMN generated_from_variant_id UUID REFERENCES product_variant(id);
ALTER TABLE product_variant ADD COLUMN generation_batch_id UUID REFERENCES image_cleanup_batch(id);
CREATE INDEX ix_product_variant_generation_batch ON product_variant (generation_batch_id);
