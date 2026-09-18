-- Who is ordering (a person or a business name) and how they want it delivered.
-- Both nullable: orders placed before this migration have neither, and
-- delivery_method is meaningless for BRANCH_PICKUP. The shop never charges for
-- delivery (the customer pays the rider/transporter directly), so this is purely
-- a handling note for staff — it does not affect any total.
ALTER TABLE customer_order
    ADD COLUMN customer_name   VARCHAR(200),
    ADD COLUMN delivery_method VARCHAR(20) CHECK (delivery_method IN ('BODA', 'VEHICLE'));
