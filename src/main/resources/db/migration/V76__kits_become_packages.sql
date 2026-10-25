-- Kits are called packages (owner, 2026-10-25). Only names change: every row keeps its id and its meaning.
--  * tables kit -> product_package, kit_component -> package_component, kit_extra_profession -> package_extra_profession
--  * columns kit_id -> package_id (both child tables) and product_variant.is_kit -> is_package
--  * the indexes and constraints named after them, so the schema does not keep saying kit
--  * the SKU of an existing package: KIT-000123 -> PKG-000123. Orders keep the SKU the line was placed with
--    (sku_snapshot), so what was printed on a pick list and shipped stays true.
-- Names are looked up before they are renamed, so a database whose constraints were named differently (an older
-- Postgres, a hand-made one) still migrates; whatever is not found is simply left as it is.

ALTER TABLE kit RENAME TO product_package;
ALTER TABLE kit_component RENAME TO package_component;
ALTER TABLE kit_extra_profession RENAME TO package_extra_profession;

ALTER TABLE package_component RENAME COLUMN kit_id TO package_id;
ALTER TABLE package_extra_profession RENAME COLUMN kit_id TO package_id;
ALTER TABLE product_variant RENAME COLUMN is_kit TO is_package;

DO $$
DECLARE
    r RECORD;
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
END $$;

UPDATE product_variant
SET sku = 'PKG-' || substring(sku FROM 5)
WHERE is_package AND sku LIKE 'KIT-%'
  AND NOT EXISTS (SELECT 1 FROM product_variant other WHERE other.sku = 'PKG-' || substring(product_variant.sku FROM 5));
