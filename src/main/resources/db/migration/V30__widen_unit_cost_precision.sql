-- Inventory redesign Phase 0 (2026-09-27), D7: V8's own comment said unit_cost stays at 2dp
-- because it's "a direct human-entered or sale-price figure, not a derived/rounded average" —
-- true for an inbound (receipt/PO) line, where the caller does supply it directly, but not true
-- for an outbound (sale/write-off) line: StockPostingService.applyMovement's outbound branch sets
-- unitCostForEntry = balance.getAvgCost(), the same 4dp moving average V8 already widened
-- avg_cost/stock_value for. Every outbound stock_ledger_entry/stock_document_line.unit_cost, and
-- every CustomerOrderItem.unit_cost_at_sale sourced from it, was silently truncating that 4dp
-- figure to 2dp on write — the same class of precision loss V8 fixed for avg_cost, just missed
-- here because it only considered the inbound case.
-- 16 total digits, not 14: these columns held 12 whole digits at 2dp, and adding two decimals must not
-- take two whole digits away (a migration that shrinks a column fails on any row that no longer fits).
ALTER TABLE stock_ledger_entry ALTER COLUMN unit_cost TYPE NUMERIC(16,4);
ALTER TABLE stock_document_line ALTER COLUMN unit_cost TYPE NUMERIC(16,4);
ALTER TABLE customer_order_item ALTER COLUMN unit_cost_at_sale TYPE NUMERIC(16,4);
