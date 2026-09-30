-- A variant's optional single wholesale tier: reaching wholesale_min_qty in a cart line drops the
-- WHOLE line's per-unit price to wholesale_price. Both null = no tier; the service layer enforces
-- they're set/cleared together (same pattern as deal_price/deal_ends_at, V21). Cheapest-wins against
-- an active flash deal / promo campaign is resolved in EffectivePriceResolver, not the DB.
ALTER TABLE product_variant ADD COLUMN wholesale_min_qty INTEGER;
ALTER TABLE product_variant ADD COLUMN wholesale_price NUMERIC(14,2);

-- Snapshot of whether wholesale pricing was actually applied to this line/order item — lets the
-- cart and order history show "wholesale price applied" without re-deriving it from qty vs. a
-- tier that may since have changed or been cleared.
ALTER TABLE cart_item ADD COLUMN wholesale_applied BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE customer_order_item ADD COLUMN wholesale_applied BOOLEAN NOT NULL DEFAULT false;
