-- The one automatic "we still need your M-PESA code" reminder an unpaid order gets (owner's call, 2026-10-06: one,
-- fifteen minutes after the order is placed, and only if no code has arrived). NULL means it has not been sent.
--
-- Every order that already exists is marked as done, so deploying this can never email a backlog of old unpaid
-- orders: only orders placed from now on are ever reminded.
ALTER TABLE customer_order ADD COLUMN payment_reminder_sent_at TIMESTAMPTZ;

UPDATE customer_order SET payment_reminder_sent_at = now();
