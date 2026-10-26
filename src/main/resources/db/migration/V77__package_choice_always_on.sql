-- The customer can always choose the colour or size of a product inside a package, even when staff added one particular
-- option (owner, 2026-10-26; this reverses V74's "off unless staff switch it on" of 23 Oct). Every product of every package
-- now allows it, and a new one does too. The option staff added stays the one the package starts on.
ALTER TABLE package_component ALTER COLUMN customer_can_change SET DEFAULT TRUE;
UPDATE package_component SET customer_can_change = TRUE WHERE NOT customer_can_change;
