-- Tracks how much of a sold order line has already been returned, so a
-- second return attempt can be capped at what's actually still outstanding
-- instead of trusting the caller not to double-return the same units.
ALTER TABLE customer_order_item
    ADD COLUMN returned_qty NUMERIC(14, 3) NOT NULL DEFAULT 0;
