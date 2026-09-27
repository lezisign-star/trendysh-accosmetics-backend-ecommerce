-- Inventory redesign Phase 0 (2026-09-27): stock_balance and stock_reservation are mutated under a
-- PESSIMISTIC_WRITE lock, but neither carries a @Version column. Hibernate's own persistence
-- context returns the already-managed instance for a lock request on an entity id it already holds
-- in this transaction, without re-reading the fresh column values — confirmed against Hibernate
-- 6.6.53 (the version Spring Boot 3.5.16 pins). That silently hands back stale on_hand/reserved_qty
-- in exactly the contended cases these locks exist to protect (order dispatch, reservation
-- release/expiry, and post()'s own reservation check), which can both undercount reserved_qty
-- (overselling) and corrupt on_hand on a later write-back. Adding @Version does not remove the need
-- to avoid reading these rows before locking them (fixed in the same commit, application-side) — it
-- is the backstop that turns any remaining stale-write attempt into a loud
-- ObjectOptimisticLockingFailureException instead of a silent lost update.
ALTER TABLE stock_balance ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE stock_reservation ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
