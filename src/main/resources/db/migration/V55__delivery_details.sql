-- Optional extras a customer can give about where a delivery goes and who receives it (2026-10-07, owner's request:
-- "more delivery location details ... even if they will be optional"). Every column is nullable and nothing is
-- backfilled: an order that pre-dates this simply has none, and so does any order that gave none.
--
-- Which of them an order can hold depends on how it travels (see DeliveryDetails): a rider's doorstep delivery (BODA)
-- takes the street, building and floor or door; a drop at a stage (MATATU) takes directions and who collects; a courier
-- or sacco parcel (COURIER) takes the courier's office. Who to ring on arrival is not here: that is the existing
-- delivery_contact_phone (V47).
ALTER TABLE customer_order
    ADD COLUMN delivery_estate         VARCHAR(120),
    ADD COLUMN delivery_street         VARCHAR(120),
    ADD COLUMN delivery_building       VARCHAR(120),
    ADD COLUMN delivery_unit           VARCHAR(60),
    ADD COLUMN delivery_courier_office VARCHAR(120),
    ADD COLUMN delivery_directions     VARCHAR(400),
    ADD COLUMN delivery_instructions   VARCHAR(400),
    ADD COLUMN delivery_receiver_name  VARCHAR(100),
    ADD COLUMN delivery_time_window    VARCHAR(20);

-- NULL passes a CHECK, so "no preference" needs no value of its own.
ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_delivery_time_window_check
    CHECK (delivery_time_window IN ('MORNING', 'AFTERNOON', 'EVENING', 'ANY_TIME'));
