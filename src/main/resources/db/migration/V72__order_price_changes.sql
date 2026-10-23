-- A price changed on a line while staff enter an order for a customer (owner, 2026-10-23): a customer who phones may have
-- been promised a better price, and the shop wants to see afterwards what was changed, when, why and who agreed to it.
-- One row per changed line, kept for good. The line itself keeps the price the customer was charged (customer_order_item.
-- unit_price, as ever); this row keeps what the shop's own price was, the reason, who entered it, and the verbal OK:
--  * confirmer_*: the owner or manager the staff member spoke to (a person who may approve a price change); what_was_said is
--    what was said between them, in the staff member's words, so they can remind each other later.
--  * ack_status: NOT_NEEDED when the person changing it may approve price changes themselves (and named nobody);
--    PENDING while the named confirmer has not yet answered on their dashboard; CONFIRMED or DISPUTED once they have.
--    A dispute changes nothing about the order, it puts the price change in front of the owner.
-- amount_off is (list - new) x qty: positive for money given away, negative when the price was raised. Names are kept beside
-- the staff ids so the record still reads the same if an account is later removed (ON DELETE SET NULL).
CREATE TABLE order_price_change (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL REFERENCES customer_order (id),
    order_item_id   UUID NOT NULL REFERENCES customer_order_item (id),
    line_label      VARCHAR(300) NOT NULL,
    qty             NUMERIC(14, 3) NOT NULL,
    list_unit_price NUMERIC(14, 2) NOT NULL,
    new_unit_price  NUMERIC(14, 2) NOT NULL CHECK (new_unit_price >= 0),
    amount_off      NUMERIC(14, 2) NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    entered_by_id   UUID REFERENCES staff_user (id) ON DELETE SET NULL,
    entered_by_name VARCHAR(200) NOT NULL,
    confirmer_id    UUID REFERENCES staff_user (id) ON DELETE SET NULL,
    confirmer_name  VARCHAR(200),
    what_was_said   VARCHAR(1000),
    ack_status      VARCHAR(20) NOT NULL CHECK (ack_status IN ('NOT_NEEDED', 'PENDING', 'CONFIRMED', 'DISPUTED')),
    ack_at          TIMESTAMPTZ,
    ack_by_id       UUID REFERENCES staff_user (id) ON DELETE SET NULL,
    ack_by_name     VARCHAR(200),
    ack_note        VARCHAR(1000),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX order_price_change_order_idx ON order_price_change (order_id);
CREATE INDEX order_price_change_created_idx ON order_price_change (created_at DESC);
CREATE INDEX order_price_change_waiting_idx ON order_price_change (confirmer_id) WHERE ack_status = 'PENDING';
