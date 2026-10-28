-- Which Terms of use and Privacy notice a shopper agreed to when placing an order (owner, 2026-10-28: two boxes at checkout).
--  * customer_order.terms_version / privacy_version: the version of each page the shopper ticked (the date it was last
--    changed, as the website shows it, e.g. 2026-10-28).
--  * customer_order.policies_accepted_at: when the order carrying those ticks was placed.
--    All three stay null for an order staff entered for a customer and for every order placed before the boxes existed.
-- Safe to run again; the old code never reads these columns.
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS terms_version VARCHAR(20);
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS privacy_version VARCHAR(20);
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS policies_accepted_at TIMESTAMPTZ;
