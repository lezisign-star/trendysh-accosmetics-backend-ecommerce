-- Who the rider should actually call at drop-off, distinct from the phone used for M-Pesa/account
-- contact (TUMABODA_PARITY_PLAN.md Section A) -- a warehouse/gate guard receiving for a B2B buyer,
-- for instance, is a different person than whoever placed the order and pays.
ALTER TABLE customer_order
    ADD COLUMN delivery_contact_phone VARCHAR(20);
