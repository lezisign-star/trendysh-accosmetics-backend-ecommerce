-- TumaBoda API delivery (2026-09-28), modelled on how Moments Packaging tracks a booked delivery.
--
-- An order gets a drop-off point when the customer picks a TumaBoda delivery with an address the
-- checkout could locate; the quoted fee is charged in the order total (delivery_fee, which has existed
-- since V9 but was always 0). A rider is booked with TumaBoda when staff mark the order READY, and
-- TumaBoda's webhook (or the reconciliation poll) then reports the delivery's progress.
--
-- Orders with no drop-off point are arranged by phone exactly as before (V35's transporter fields).
-- tumaboda_rerouted marks an order staff moved back to that manual route after a booking problem.
--
-- This reverses V12's "the shop never charges a delivery fee" for exactly one case. V12 enforced that
-- with CHECK (delivery_fee = 0); it is replaced below by a check that still refuses a fee on every
-- order except one carrying a TumaBoda drop-off point (the only kind whose fee is quoted by TumaBoda
-- and charged at checkout), so a future write path still cannot invent a fee by accident.

ALTER TABLE customer_order
    ADD COLUMN dropoff_lat               NUMERIC(10, 7),
    ADD COLUMN dropoff_lng               NUMERIC(10, 7),
    ADD COLUMN tumaboda_delivery_id      VARCHAR(100),
    ADD COLUMN tumaboda_delivery_number  VARCHAR(100),
    ADD COLUMN tumaboda_tracking_code    VARCHAR(100),
    ADD COLUMN tumaboda_tracking_url     VARCHAR(500),
    ADD COLUMN tumaboda_status           VARCHAR(40),
    ADD COLUMN tumaboda_cost             NUMERIC(14, 2),
    ADD COLUMN tumaboda_rider_id         VARCHAR(100),
    ADD COLUMN tumaboda_last_webhook_at  TIMESTAMPTZ,
    ADD COLUMN tumaboda_booking_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN tumaboda_next_retry_at    TIMESTAMPTZ,
    ADD COLUMN tumaboda_booking_failure  VARCHAR(500),
    ADD COLUMN tumaboda_restart_count    INT NOT NULL DEFAULT 0,
    ADD COLUMN tumaboda_rerouted         BOOLEAN NOT NULL DEFAULT FALSE;

-- A TumaBoda delivery id belongs to exactly one order: a webhook is matched to its order by it.
CREATE UNIQUE INDEX uq_customer_order_tumaboda_delivery
    ON customer_order (tumaboda_delivery_id) WHERE tumaboda_delivery_id IS NOT NULL;

-- Feeds the booking retry job.
CREATE INDEX idx_customer_order_tumaboda_retry
    ON customer_order (tumaboda_next_retry_at) WHERE tumaboda_next_retry_at IS NOT NULL;

-- V12's blanket "no delivery fee" rule, narrowed: a fee is allowed only alongside a drop-off point.
ALTER TABLE customer_order
    DROP CONSTRAINT customer_order_delivery_fee_always_zero;

ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_delivery_fee_only_for_tumaboda
        CHECK (delivery_fee = 0 OR (delivery_fee > 0 AND dropoff_lat IS NOT NULL AND dropoff_lng IS NOT NULL));
