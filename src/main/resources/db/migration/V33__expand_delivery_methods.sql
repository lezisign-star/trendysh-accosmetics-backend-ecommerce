-- "Vehicle / Matatu" was one combined checkout option; it now splits into a
-- distinct MATATU and a distinct COURIER (formal courier company) choice, and
-- BODA is customer-facing-labelled "TumaBoda" going forward. Existing rows
-- said VEHICLE meaning matatu/vehicle transport, so they become MATATU here —
-- COURIER is a new choice for orders placed from now on, not a relabelling of
-- any existing data.

-- V14's inline CHECK only allows ('BODA', 'VEHICLE'), so renaming VEHICLE rows to MATATU
-- below would violate it on any database that already holds a VEHICLE order — and Flyway would
-- then fail this migration on every start. Widen the constraint to the values involved first;
-- V34 swaps it for the final three-value vocabulary once the rows have been converted.
ALTER TABLE customer_order DROP CONSTRAINT customer_order_delivery_method_check;
ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_delivery_method_check
    CHECK (delivery_method IN ('BODA', 'VEHICLE', 'MATATU', 'COURIER'));

UPDATE customer_order SET delivery_method = 'MATATU' WHERE delivery_method = 'VEHICLE';
