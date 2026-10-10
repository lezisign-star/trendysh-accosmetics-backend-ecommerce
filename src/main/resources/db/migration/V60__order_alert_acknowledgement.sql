-- A new order rings in the admin until a member of staff stops it, and stopping it stops it for everyone (owner,
-- 2026-10-10). Whether an order has been seen is therefore a fact about the order, not about one browser tab.
-- Every order that exists today counts as already seen, so nothing rings the moment this goes live.
ALTER TABLE customer_order ADD COLUMN alert_acknowledged_at TIMESTAMPTZ;
ALTER TABLE customer_order ADD COLUMN alert_acknowledged_by VARCHAR(120);
UPDATE customer_order SET alert_acknowledged_at = now() WHERE alert_acknowledged_at IS NULL;
CREATE INDEX ix_customer_order_unacknowledged ON customer_order (created_at) WHERE alert_acknowledged_at IS NULL;
