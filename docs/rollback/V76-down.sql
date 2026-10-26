-- Takes V76 (kits become packages) back: the tables, columns and SKUs get their kit names again, with all their data.
-- For when the code from before commit 89b5ffb has to run again (that code reads the tables "kit", "kit_component",
-- "kit_extra_profession" and the column "product_variant.is_kit", and cannot start on the V76 schema).
--
--  1. Stop the NEW instance first (or let nothing write), so nothing is writing while the names change.
--  2. Run this file in one go against the shop's database:  psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f V76-down.sql
--  3. Deploy the old code.
--
-- What it does not do: it leaves the index and constraint names as V76 renamed them (Hibernate does not look at those names,
-- and V76 skips a rename whose target name is already taken, so applying V76 again afterwards works). It removes V76 and every later
-- migration from Flyway's history, so the next deploy of the new code applies them again.
-- A package made after V76 gets the kit SKU prefix back too (KIT-n); orders keep the SKU they were placed with either way.
-- Proven by PackageRenameRollbackTest: V76, this file, the old table and column names answering queries, then V76 again.
BEGIN;
SET LOCAL lock_timeout = '15s';

UPDATE product_variant SET sku = 'KIT-' || substring(sku FROM 5)
WHERE is_package AND sku LIKE 'PKG-%'
  AND NOT EXISTS (SELECT 1 FROM product_variant o WHERE o.sku = 'KIT-' || substring(product_variant.sku FROM 5));

ALTER TABLE product_variant RENAME COLUMN is_package TO is_kit;
ALTER TABLE package_extra_profession RENAME COLUMN package_id TO kit_id;
ALTER TABLE package_component RENAME COLUMN package_id TO kit_id;
ALTER TABLE package_extra_profession RENAME TO kit_extra_profession;
ALTER TABLE package_component RENAME TO kit_component;
ALTER TABLE product_package RENAME TO kit;

-- every migration from V76 on: later ones (V77: customers can always choose a package product's option) only change values
-- the old code reads as they are, and they all apply again, in order, when the new code comes back
DELETE FROM flyway_schema_history WHERE version ~ '^[0-9]+$' AND version::int >= 76;
COMMIT;
