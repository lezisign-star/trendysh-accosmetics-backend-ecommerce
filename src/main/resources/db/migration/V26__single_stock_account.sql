-- Founder decision (2026-09-24): eliminate branches/locations completely, not just hide
-- them. This migration removes both `branch` and `stock_location` as tables. What
-- replaces them is NOT a location — it's a 4-value account (SHOP/SUPPLIER/CUSTOMER/
-- ADJUSTMENT) that keeps the double-entry stock ledger's correctness (every movement
-- still has two sides, and StockBalanceRebuildService's "everything sums to zero"
-- reconciliation still holds) with no table, no rows and nothing branch-shaped. See
-- HANDOFF.md for the full reasoning; this file is the mechanical migration only.
--
-- This is safe to run because Phase 0 of the redesign confirmed, on both dev and
-- production, that no DAMAGED/IN_TRANSIT location holds stock, no TRANSFER stock
-- document exists (transfers were designed but never built), and no reservation has
-- source TRANSFER_OUT. The guards below re-check all three and refuse to run
-- otherwise, so this migration cannot silently discard real stock.

DO $$
DECLARE
    bad_damaged_or_transit NUMERIC;
    bad_transfer_docs INT;
    bad_transfer_reservations INT;
BEGIN
    SELECT coalesce(sum(abs(b.on_hand_qty) + abs(b.reserved_qty)), 0) INTO bad_damaged_or_transit
    FROM stock_balance b
    JOIN stock_location l ON l.id = b.location_id
    WHERE l.location_type IN ('DAMAGED', 'IN_TRANSIT');

    IF bad_damaged_or_transit <> 0 THEN
        RAISE EXCEPTION 'V26 refuses to run: a DAMAGED or IN_TRANSIT location still holds stock (on_hand+reserved sums to %). Write it off with POST /stock/adjust before migrating.', bad_damaged_or_transit;
    END IF;

    SELECT count(*) INTO bad_transfer_docs FROM stock_document WHERE document_type = 'TRANSFER';
    IF bad_transfer_docs > 0 THEN
        RAISE EXCEPTION 'V26 refuses to run: % TRANSFER stock_document row(s) exist. This migration assumes transfers were never built — investigate before migrating.', bad_transfer_docs;
    END IF;

    SELECT count(*) INTO bad_transfer_reservations FROM stock_reservation WHERE source = 'TRANSFER_OUT';
    IF bad_transfer_reservations > 0 THEN
        RAISE EXCEPTION 'V26 refuses to run: % stock_reservation row(s) have source TRANSFER_OUT. Investigate before migrating.', bad_transfer_reservations;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- stock_ledger_entry: location_id -> account. This is the only place existing
-- ledger HISTORY is touched, and only its shape (one FK column becomes one enum
-- column) — no qty_change/qty_after value is altered. The append-only trigger is
-- disabled only for this one backfill statement, then re-enabled immediately.
-- ---------------------------------------------------------------------------
ALTER TABLE stock_ledger_entry ADD COLUMN account VARCHAR(20);

ALTER TABLE stock_ledger_entry DISABLE TRIGGER trg_stock_ledger_entry_no_update;

UPDATE stock_ledger_entry e
SET account = CASE l.location_type
    WHEN 'SALES_FLOOR' THEN 'SHOP'
    WHEN 'BACKSTORE'   THEN 'SHOP'
    WHEN 'DAMAGED'     THEN 'SHOP'
    ELSE l.location_type
END
FROM stock_location l
WHERE l.id = e.location_id;

ALTER TABLE stock_ledger_entry ENABLE TRIGGER trg_stock_ledger_entry_no_update;

ALTER TABLE stock_ledger_entry
    ALTER COLUMN account SET NOT NULL,
    ADD CONSTRAINT stock_ledger_entry_account_check CHECK (account IN ('SHOP', 'SUPPLIER', 'CUSTOMER', 'ADJUSTMENT'));

DROP INDEX idx_sle_variant_location_posted;
CREATE INDEX idx_sle_variant_account_posted ON stock_ledger_entry (variant_id, account, posted_at DESC, id DESC);

ALTER TABLE stock_ledger_entry DROP COLUMN location_id;

COMMENT ON COLUMN stock_ledger_entry.qty_after IS
    'Running total for this (variant, account) pair. Rows written before the 2026-09 '
    'branch-removal migration (V26) recorded this per (variant, physical location); '
    'SALES_FLOOR/BACKSTORE/DAMAGED all collapsed into SHOP at that point, so a '
    'pre-migration qty_after is not directly comparable across the merge — reconcile '
    'by SUM(qty_change) per (variant, account), which is unaffected by the merge, not '
    'by reading qty_after in isolation.';

-- ---------------------------------------------------------------------------
-- stock_balance: aggregate the physical locations into SHOP, keep the virtual
-- accounts as they were (each already had exactly one location row per branch-less
-- virtual type, so this is a rename, not a real aggregation, for those three).
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE stock_balance_agg AS
SELECT
    b.variant_id,
    CASE l.location_type
        WHEN 'SALES_FLOOR' THEN 'SHOP'
        WHEN 'BACKSTORE'   THEN 'SHOP'
        WHEN 'DAMAGED'     THEN 'SHOP'
        ELSE l.location_type
    END AS account,
    SUM(b.on_hand_qty) AS on_hand_qty,
    SUM(b.reserved_qty) AS reserved_qty,
    SUM(b.stock_value) AS stock_value,
    CASE WHEN SUM(b.on_hand_qty) > 0
         THEN round(SUM(b.stock_value) / SUM(b.on_hand_qty), 4)
         ELSE MAX(b.avg_cost)
    END AS avg_cost,
    MAX(b.last_ledger_entry_id) AS last_ledger_entry_id
FROM stock_balance b
JOIN stock_location l ON l.id = b.location_id
GROUP BY b.variant_id, CASE l.location_type
    WHEN 'SALES_FLOOR' THEN 'SHOP'
    WHEN 'BACKSTORE'   THEN 'SHOP'
    WHEN 'DAMAGED'     THEN 'SHOP'
    ELSE l.location_type
END;

DELETE FROM stock_balance;

ALTER TABLE stock_balance
    DROP CONSTRAINT stock_balance_variant_id_location_id_key,
    DROP COLUMN location_id,
    ADD COLUMN account VARCHAR(20),
    ADD CONSTRAINT stock_balance_account_check CHECK (account IN ('SHOP', 'SUPPLIER', 'CUSTOMER', 'ADJUSTMENT'));

INSERT INTO stock_balance (id, variant_id, account, on_hand_qty, reserved_qty, avg_cost, stock_value, last_ledger_entry_id, created_at, updated_at)
SELECT gen_random_uuid(), variant_id, account, on_hand_qty, reserved_qty, coalesce(avg_cost, 0), stock_value, last_ledger_entry_id, now(), now()
FROM stock_balance_agg;

ALTER TABLE stock_balance
    ALTER COLUMN account SET NOT NULL,
    ADD CONSTRAINT uq_stock_balance_variant_account UNIQUE (variant_id, account);

DROP TABLE stock_balance_agg;

-- ---------------------------------------------------------------------------
-- stock_reservation: no account column at all — a reservation always holds SHOP
-- stock now (enforced in code, StockPostingService.post, not by a DB constraint,
-- since the account concept doesn't apply to a hold the way it does to a ledger
-- movement).
-- ---------------------------------------------------------------------------
DROP INDEX idx_reservation_active_lookup;
ALTER TABLE stock_reservation DROP COLUMN location_id;
CREATE INDEX idx_reservation_active_by_variant ON stock_reservation (variant_id) WHERE status = 'ACTIVE';

ALTER TABLE stock_reservation
    DROP CONSTRAINT stock_reservation_source_check,
    ADD CONSTRAINT stock_reservation_source_check
        CHECK (source IN ('ONLINE_CART', 'ONLINE_ORDER', 'POS_TICKET', 'MANUAL_HOLD'));

-- ---------------------------------------------------------------------------
-- stock_document_line: source/target location -> source/target account.
-- ---------------------------------------------------------------------------
ALTER TABLE stock_document_line
    ADD COLUMN source_account VARCHAR(20),
    ADD COLUMN target_account VARCHAR(20);

UPDATE stock_document_line dl
SET source_account = CASE sl.location_type
        WHEN 'SALES_FLOOR' THEN 'SHOP' WHEN 'BACKSTORE' THEN 'SHOP' WHEN 'DAMAGED' THEN 'SHOP'
        ELSE sl.location_type END
FROM stock_location sl
WHERE sl.id = dl.source_location_id;

UPDATE stock_document_line dl
SET target_account = CASE tl.location_type
        WHEN 'SALES_FLOOR' THEN 'SHOP' WHEN 'BACKSTORE' THEN 'SHOP' WHEN 'DAMAGED' THEN 'SHOP'
        ELSE tl.location_type END
FROM stock_location tl
WHERE tl.id = dl.target_location_id;

ALTER TABLE stock_document_line
    DROP CONSTRAINT chk_line_has_a_side,
    DROP COLUMN source_location_id,
    DROP COLUMN target_location_id,
    ADD CONSTRAINT stock_document_line_source_account_check CHECK (source_account IN ('SHOP', 'SUPPLIER', 'CUSTOMER', 'ADJUSTMENT')),
    ADD CONSTRAINT stock_document_line_target_account_check CHECK (target_account IN ('SHOP', 'SUPPLIER', 'CUSTOMER', 'ADJUSTMENT')),
    ADD CONSTRAINT chk_line_has_a_side CHECK (source_account IS NOT NULL OR target_account IS NOT NULL);

-- ---------------------------------------------------------------------------
-- stock_document: drop branch_id; TRANSFER is removed from the document-type
-- vocabulary (transfers were designed but never built, per the Phase 0 check above).
-- ---------------------------------------------------------------------------
DROP INDEX idx_stock_document_branch;
ALTER TABLE stock_document DROP COLUMN branch_id;

ALTER TABLE stock_document
    DROP CONSTRAINT stock_document_document_type_check,
    ADD CONSTRAINT stock_document_document_type_check CHECK (document_type IN (
        'GOODS_RECEIPT', 'SALE_ONLINE', 'SALE_POS', 'CUSTOMER_RETURN',
        'SUPPLIER_RETURN', 'ADJUSTMENT', 'STOCK_COUNT', 'WRITE_OFF'
    ));

-- ---------------------------------------------------------------------------
-- customer_order: drop the fulfilment branch; BRANCH_PICKUP -> PICKUP. The pickup
-- address was already fixed text in the frontend, never read from branch, so
-- nothing customer-facing changes.
-- ---------------------------------------------------------------------------
ALTER TABLE customer_order DROP COLUMN fulfilment_branch_id;

ALTER TABLE customer_order DROP CONSTRAINT customer_order_fulfilment_type_check;

UPDATE customer_order SET fulfilment_type = 'PICKUP' WHERE fulfilment_type = 'BRANCH_PICKUP';

ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_fulfilment_type_check CHECK (fulfilment_type IN ('DELIVERY_COURIER', 'PICKUP'));

-- ---------------------------------------------------------------------------
-- Staff: branch assignment and branch-scoped permissions/roles disappear.
-- ---------------------------------------------------------------------------
DROP TABLE staff_branch_assignment;

DELETE FROM staff_role_permissions
WHERE permission IN ('BRANCH_MANAGE', 'STOCK_TRANSFER_INITIATE', 'STOCK_TRANSFER_APPROVE');

UPDATE staff_role SET name = 'MANAGER', display_name = 'Manager' WHERE name = 'BRANCH_MANAGER';

-- ---------------------------------------------------------------------------
-- Last: the tables themselves.
-- ---------------------------------------------------------------------------
DROP TABLE stock_location;
DROP TABLE branch;
