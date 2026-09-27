-- Inventory redesign Phase 2 (2026-09-27): write-offs need a reason for shrinkage analytics —
-- "we lost 40 units this month" is much less useful than "28 expired, 12 damaged". Nullable:
-- only WRITE_OFF documents set it; every other document type leaves it null.
ALTER TABLE stock_document ADD COLUMN reason_code VARCHAR(20);
