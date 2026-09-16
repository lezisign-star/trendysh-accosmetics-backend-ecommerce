-- Audit finding F11 (session 3): StockPostingService computes the moving
-- average cost at 4 decimal places (RoundingMode.HALF_UP, scale 4) but these
-- columns were stored at 2dp — so stock_value (derived from the 4dp figure)
-- disagreed with on_hand_qty * avg_cost once recomputed from the stored
-- columns, and a from-ledger valuation rebuild could not reproduce values
-- byte-identically, which is exactly the acceptance criterion the
-- architecture doc's §4.4 states. Widening to match the in-memory precision
-- (cosmetics unit costs in KES genuinely need sub-cent precision after
-- averaging many receipts at different prices) instead of rounding the
-- computation down to 2dp, which would throw away real precision.
--
-- unit_cost (on stock_document_line and stock_ledger_entry) is left at 2dp
-- deliberately — it is a direct human-entered or sale-price figure, not a
-- derived/rounded average, so there is no precision mismatch to fix there.
ALTER TABLE stock_ledger_entry
    ALTER COLUMN avg_cost_after TYPE NUMERIC(14,4),
    ALTER COLUMN stock_value_after TYPE NUMERIC(14,4),
    ALTER COLUMN stock_value_difference TYPE NUMERIC(14,4);

ALTER TABLE stock_balance
    ALTER COLUMN avg_cost TYPE NUMERIC(14,4),
    ALTER COLUMN stock_value TYPE NUMERIC(14,4);
