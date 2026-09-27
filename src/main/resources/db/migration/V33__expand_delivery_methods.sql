-- "Vehicle / Matatu" was one combined checkout option; it now splits into a
-- distinct MATATU and a distinct COURIER (formal courier company) choice, and
-- BODA is customer-facing-labelled "TumaBoda" going forward. Existing rows
-- said VEHICLE meaning matatu/vehicle transport, so they become MATATU here —
-- COURIER is a new choice for orders placed from now on, not a relabelling of
-- any existing data.
UPDATE customer_order SET delivery_method = 'MATATU' WHERE delivery_method = 'VEHICLE';
