-- TumaBoda/matatu/courier delivery is arranged by phone (founder's manual-delivery
-- model — see OrderFulfilmentService's javadoc), but who was actually assigned and
-- what price was agreed was never recorded anywhere: staff knew it from the call,
-- the customer knew it from the call, and neither the admin order screen nor the
-- customer's own tracking page ever showed it. These columns are that record.
ALTER TABLE customer_order
    ADD COLUMN transporter_name VARCHAR(200),
    ADD COLUMN transporter_phone VARCHAR(20),
    ADD COLUMN agreed_delivery_price NUMERIC(14, 2);
