-- Bank collection (customer pays the bank with a per-order reference; we match
-- the bank's credit notification to it). payment_reference is the short code the
-- customer types into the paybill account / transfer narration; expires_at is the
-- end of the payment window (same instant the stock reservation lapses).
--
-- A plain UNIQUE index treats NULLs as distinct, so M-Pesa/cash records, which
-- never get a payment_reference, are unaffected (same reasoning as V11).
ALTER TABLE payment_record
    ADD COLUMN payment_reference VARCHAR(20),
    ADD COLUMN expires_at        TIMESTAMPTZ;

CREATE UNIQUE INDEX idx_payment_record_payment_reference
    ON payment_record (payment_reference);
