-- Two real branches. Codes are placeholders — rename once the founder confirms
-- branch names/addresses.
INSERT INTO branch (id, code, name, is_active, is_default_online_fulfilment) VALUES
    (gen_random_uuid(), 'BR-A', 'Branch A', true, true),
    (gen_random_uuid(), 'BR-B', 'Branch B', true, false);

INSERT INTO stock_location (id, branch_id, code, name, location_type, is_internal, is_active)
SELECT gen_random_uuid(), b.id, b.code || '/SALES_FLOOR', b.name || ' — Sales Floor', 'SALES_FLOOR', true, true
FROM branch b;

INSERT INTO stock_location (id, branch_id, code, name, location_type, is_internal, is_active)
SELECT gen_random_uuid(), b.id, b.code || '/BACKSTORE', b.name || ' — Backstore', 'BACKSTORE', true, true
FROM branch b;

INSERT INTO stock_location (id, branch_id, code, name, location_type, is_internal, is_active)
SELECT gen_random_uuid(), b.id, b.code || '/DAMAGED', b.name || ' — Damaged', 'DAMAGED', true, true
FROM branch b;

-- Global virtual locations — the "other side" of every receipt, sale,
-- transfer and adjustment, so every ledger entry is a genuine two-sided
-- movement and SUM(qty_change) per variant is always exactly zero.
INSERT INTO stock_location (id, branch_id, code, name, location_type, is_internal, is_active) VALUES
    (gen_random_uuid(), NULL, 'IN_TRANSIT', 'In Transit (inter-branch)', 'IN_TRANSIT', false, true),
    (gen_random_uuid(), NULL, 'SUPPLIER',   'Supplier (goods receipt source)', 'SUPPLIER', false, true),
    (gen_random_uuid(), NULL, 'CUSTOMER',   'Customer (sales destination)', 'CUSTOMER', false, true),
    (gen_random_uuid(), NULL, 'ADJUSTMENT', 'Adjustment / write-off', 'ADJUSTMENT', false, true);
