-- The staff side of orders (owner, 2026-10-19): a pick-and-pack list on every order that holds a kit, and orders that staff
-- enter for a customer.
--  * customer_order_item.line_no: the order the lines were entered in, so an order always lists its lines the same way and a
--    kit's own products come in the kit's order (the database used to give them back in any order). Existing lines are
--    numbered within their order in the order their rows were stored.
--  * customer_order_item.packed_at / packed_by: the tick on a product of the pick-and-pack list, and who ticked it.
--  * customer_order.placed_by: the member of staff who entered the order for a customer; null for an order the customer placed.
--  * customer_order.guest_email becomes optional: a customer who orders over the phone or on WhatsApp may have no email, and
--    an order without one is simply not emailed (the email code already skips an order with no address).
-- Columns only: no existing order or line changes except the line numbers.
ALTER TABLE customer_order_item ADD COLUMN line_no INTEGER NOT NULL DEFAULT 0;
UPDATE customer_order_item i SET line_no = n.rn
  FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY ctid) AS rn FROM customer_order_item) n
 WHERE n.id = i.id;
ALTER TABLE customer_order_item ADD COLUMN packed_at TIMESTAMPTZ;
ALTER TABLE customer_order_item ADD COLUMN packed_by VARCHAR(200);

ALTER TABLE customer_order ADD COLUMN placed_by VARCHAR(200);
ALTER TABLE customer_order ALTER COLUMN guest_email DROP NOT NULL;
