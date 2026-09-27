-- V14's inline CHECK only allowed ('BODA', 'VEHICLE') — found live: V33's data
-- migration renamed existing VEHICLE rows to MATATU, but the column's own
-- check constraint still only permitted the original two values, so any new
-- order using MATATU or COURIER was rejected at the database level even
-- though the application layer (CheckoutRequest's @Pattern) already allowed
-- them. Replace the constraint with the current three-value vocabulary.
ALTER TABLE customer_order DROP CONSTRAINT customer_order_delivery_method_check;
ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_delivery_method_check
    CHECK (delivery_method IN ('BODA', 'MATATU', 'COURIER'));
