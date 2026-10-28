-- What staff noted after calling a customer about an unpaid order (owner, 2026-10-28: an unpaid-order call list),
-- who wrote it and when. Null until someone does. Safe to run again; the old code never reads these columns.
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS chase_note VARCHAR(300);
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS chase_note_by VARCHAR(120);
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS chase_note_at TIMESTAMPTZ;
