-- Taking the shop live before every shelf is counted (owner, 2026-10-26).
--  * product_variant.stock_basis: where an option's stock number comes from.
--      NONE       never counted, set or received: no number staff stand behind
--      ESTIMATED  set in bulk ("every product at 20") to take products live; shown to staff as estimated until counted
--      COUNTED    typed by staff for that option, received, or counted in a stock take
--    Every option that already has stock history is COUNTED; the rest start at NONE.
--  * sell_without_stock on the shop (one switch), a category or a product: an option with NO number yet (stock_basis NONE)
--    covered by any of them can be ordered without a stock limit ("not tracked yet"). Nothing is held for it at checkout
--    and nothing is taken off at dispatch; the orders themselves record what was sold. Counting it in a stock take, or
--    giving it a number, makes it tracked like any other.
-- Safe to run again (docs/rollback/V76-down.sql removes V76 and every later migration from Flyway's history but leaves these
-- columns, which the old code never reads): an option's mark and every switch are kept, and an option that gained stock
-- history meanwhile becomes COUNTED.
ALTER TABLE product_variant ADD COLUMN IF NOT EXISTS stock_basis VARCHAR(12) NOT NULL DEFAULT 'NONE'
    CHECK (stock_basis IN ('NONE', 'ESTIMATED', 'COUNTED'));

UPDATE product_variant v SET stock_basis = 'COUNTED'
WHERE v.stock_basis = 'NONE' AND EXISTS (SELECT 1 FROM stock_ledger_entry e WHERE e.variant_id = v.id);

ALTER TABLE product ADD COLUMN IF NOT EXISTS sell_without_stock BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE category ADD COLUMN IF NOT EXISTS sell_without_stock BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shop_settings ADD COLUMN IF NOT EXISTS sell_without_stock BOOLEAN NOT NULL DEFAULT FALSE;
