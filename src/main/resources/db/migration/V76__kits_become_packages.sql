-- Kits are called packages (owner, 2026-10-25). Only names change: every row keeps its id and its meaning.
--  * tables kit -> product_package, kit_component -> package_component, kit_extra_profession -> package_extra_profession
--  * columns kit_id -> package_id (both child tables) and product_variant.is_kit -> is_package
--  * the indexes and constraints named after them, so the schema does not keep saying kit
--  * the SKU of an existing package: KIT-000123 -> PKG-000123. Orders keep the SKU the line was placed with
--    (sku_snapshot), so what was printed on a pick list and shipped stays true.
-- Names are looked up before they are renamed, so a database whose constraints were named differently (an older
-- Postgres, a hand-made one) still migrates; whatever is not found is simply left as it is.

-- Renaming needs a lock on each table. If a long transaction of the instance still running holds one, give up after 15
-- seconds (the whole migration rolls back and the deploy can be tried again) rather than freeze the shop behind a queue.
SET LOCAL lock_timeout = '15s';

ALTER TABLE kit RENAME TO product_package;
ALTER TABLE kit_component RENAME TO package_component;
ALTER TABLE kit_extra_profession RENAME TO package_extra_profession;

ALTER TABLE package_component RENAME COLUMN kit_id TO package_id;
ALTER TABLE package_extra_profession RENAME COLUMN kit_id TO package_id;
ALTER TABLE product_variant RENAME COLUMN is_kit TO is_package;

DO $$
DECLARE
    r RECORD;
    new_name text;
BEGIN
    -- Indexes. Renaming the index behind a primary key or a unique constraint renames the constraint with it.
    FOR r IN SELECT * FROM (VALUES
        ('idx_kit_profession',                           'idx_package_profession'),
        ('kit_pkey',                                     'product_package_pkey'),
        ('kit_variant_id_key',                           'product_package_variant_id_key'),
        ('idx_kit_component_kit',                        'idx_package_component_package'),
        ('idx_kit_component_variant',                    'idx_package_component_variant'),
        ('kit_component_kit_id_component_variant_id_key', 'package_component_package_id_component_variant_id_key'),
        ('kit_component_pkey',                           'package_component_pkey'),
        ('idx_kit_extra_profession_profession',          'idx_package_extra_profession_profession'),
        ('kit_extra_profession_pkey',                    'package_extra_profession_pkey')
    ) AS t(old_name, new_name)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = current_schema() AND c.relname = r.old_name AND c.relkind = 'i')
           AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                           WHERE n.nspname = current_schema() AND c.relname = r.new_name) THEN
            EXECUTE format('ALTER INDEX %I RENAME TO %I', r.old_name, r.new_name);
        END IF;
    END LOOP;

    -- Foreign keys and checks (not backed by an index of their own).
    FOR r IN SELECT * FROM (VALUES
        ('product_package',          'kit_profession_id_fkey',                         'product_package_profession_id_fkey'),
        ('product_package',          'kit_variant_id_fkey',                            'product_package_variant_id_fkey'),
        ('package_component',        'kit_component_component_variant_id_fkey',        'package_component_component_variant_id_fkey'),
        ('package_component',        'kit_component_kit_id_fkey',                      'package_component_package_id_fkey'),
        ('package_component',        'kit_component_qty_check',                        'package_component_qty_check'),
        ('package_extra_profession', 'kit_extra_profession_kit_id_fkey',               'package_extra_profession_package_id_fkey'),
        ('package_extra_profession', 'kit_extra_profession_profession_id_fkey',        'package_extra_profession_profession_id_fkey')
    ) AS t(table_name, old_name, new_name)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_constraint k
                   WHERE k.conrelid = (current_schema() || '.' || r.table_name)::regclass AND k.conname = r.old_name)
           AND NOT EXISTS (SELECT 1 FROM pg_constraint k
                           WHERE k.conrelid = (current_schema() || '.' || r.table_name)::regclass AND k.conname = r.new_name) THEN
            EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I', r.table_name, r.old_name, r.new_name);
        END IF;
    END LOOP;

    -- NOT NULL constraints. PostgreSQL 18 lists them in pg_constraint, named <table>_<column>_not_null when the column was
    -- made; renaming the table or the column does not rename them. Older versions have none, so this finds nothing there.
    FOR r IN
        SELECT c.relname AS table_name, k.conname AS old_name
        FROM pg_constraint k
        JOIN pg_class c ON c.oid = k.conrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = current_schema() AND k.contype = 'n' AND k.conname LIKE '%kit%'
          AND c.relname IN ('product_package', 'package_component', 'package_extra_profession', 'product_variant')
    LOOP
        new_name := replace(replace(replace(replace(r.old_name,
                'kit_extra_profession', 'package_extra_profession'), 'kit_component', 'package_component'),
                '_kit_id_', '_package_id_'), '_is_kit_', '_is_package_');
        IF left(new_name, 4) = 'kit_' THEN
            new_name := 'product_package_' || substr(new_name, 5);
        END IF;
        IF new_name <> r.old_name
           AND NOT EXISTS (SELECT 1 FROM pg_constraint k
                           WHERE k.conrelid = (current_schema() || '.' || r.table_name)::regclass AND k.conname = new_name) THEN
            EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I', r.table_name, r.old_name, new_name);
        END IF;
    END LOOP;
END $$;

UPDATE product_variant
SET sku = 'PKG-' || substring(sku FROM 5)
WHERE is_package AND sku LIKE 'KIT-%'
  AND NOT EXISTS (SELECT 1 FROM product_variant other WHERE other.sku = 'PKG-' || substring(product_variant.sku FROM 5));
