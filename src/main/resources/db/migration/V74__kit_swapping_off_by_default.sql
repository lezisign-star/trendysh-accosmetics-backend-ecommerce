-- Customers swapping a product's colour or size inside a kit is OFF unless staff switch it on for that product
-- (owner, 2026-10-23). V69 switched it on for every kit; no kit was live then, so every product of every kit goes back to
-- off, and a new one starts off.
ALTER TABLE kit_component ALTER COLUMN customer_can_change SET DEFAULT FALSE;
UPDATE kit_component SET customer_can_change = FALSE WHERE customer_can_change;
