-- Customers can swap the colour or size of a product inside a kit (owner, 2026-10-19). The kit's contents stay as staff set
-- them (no product is added or taken out); a customer only picks a different variant of a product that is already in the
-- kit, and the price follows (package price + quantity x (picked price - default price), see KitService).
--  * kit_component.customer_can_change: staff may turn the swap off for one product of a kit (on for every existing kit).
--  * cart_item.choice_key: the same kit with different picks is a different basket line, so the line is now identified by
--    (cart, variant, choice_key); '' means "all defaults", which is what every existing line is.
--  * cart_item_choice: the picks of one line, keyed by the DEFAULT variant (a component row goes away when staff take the
--    product out of the kit and a new one is made if they add it back, so the row is not a stable key, but a product can only
--    be in a kit once, so its default variant is).
-- Columns and a new table only: no existing row is rewritten.
ALTER TABLE kit_component ADD COLUMN customer_can_change BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE cart_item ADD COLUMN choice_key VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE cart_item DROP CONSTRAINT cart_item_cart_id_variant_id_key;
ALTER TABLE cart_item ADD CONSTRAINT cart_item_cart_variant_choice_key UNIQUE (cart_id, variant_id, choice_key);

CREATE TABLE cart_item_choice (
    cart_item_id       UUID NOT NULL REFERENCES cart_item(id) ON DELETE CASCADE,
    default_variant_id UUID NOT NULL REFERENCES product_variant(id),
    chosen_variant_id  UUID NOT NULL REFERENCES product_variant(id),
    PRIMARY KEY (cart_item_id, default_variant_id)
);
