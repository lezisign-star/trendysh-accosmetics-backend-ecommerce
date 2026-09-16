-- Audit finding F10 (session 3): StockDocumentNoGenerator used a random
-- 4-digit suffix per prefix per day (9000 possible values). By the birthday
-- bound, ~120 documents of one type in a day already gives roughly a 55%
-- chance of a collision, and 300 POS sales in a day gives well over 99% — a
-- document_no UNIQUE violation mid-sale at the till. This table backs an
-- atomic, race-free per-(type, day) counter instead.
CREATE TABLE stock_document_counter (
    counter_key VARCHAR(30) PRIMARY KEY,  -- e.g. "GRN-20260912"
    next_value  BIGINT NOT NULL DEFAULT 1
);
