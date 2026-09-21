-- Append-only records behind bank collection. Neither table is ever deleted from:
-- every claim and every bank credit stays for reconciliation and eTIMS/tax records;
-- resolving one only changes its status and stamps who did it and when.

-- A customer says "I've paid": transaction code + amount, awaiting staff verification.
CREATE TABLE payment_claim (
    id                UUID PRIMARY KEY,
    payment_record_id UUID NOT NULL REFERENCES payment_record(id),
    transaction_code  VARCHAR(60) NOT NULL,
    amount_claimed    NUMERIC(14,2) NOT NULL,
    paid_on           DATE,
    payer_name        VARCHAR(200),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    resolved_by       VARCHAR(255),
    resolved_at       TIMESTAMPTZ,
    resolution_note   TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_claim_status ON payment_claim (status, created_at);
CREATE INDEX idx_payment_claim_payment ON payment_claim (payment_record_id);

-- A credit that landed in the bank account (recorded by staff from the statement now;
-- by the bank's notification / reconciliation job later). bank_txn_id is the bank's own
-- transaction id and is unique, so re-recording or a retried notification is harmless.
CREATE TABLE bank_credit (
    id                UUID PRIMARY KEY,
    bank_txn_id       VARCHAR(100) NOT NULL,
    amount            NUMERIC(14,2) NOT NULL,
    payer_name        VARCHAR(200),
    payer_phone       VARCHAR(30),
    narration         TEXT,
    received_at       TIMESTAMPTZ NOT NULL,
    source            VARCHAR(20) NOT NULL CHECK (source IN ('MANUAL', 'IPN', 'RECONCILIATION')),
    status            VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED'
        CHECK (status IN ('UNMATCHED', 'MATCHED', 'IGNORED')),
    payment_record_id UUID REFERENCES payment_record(id),
    resolved_by       VARCHAR(255),
    resolved_at       TIMESTAMPTZ,
    resolution_note   TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_bank_credit_txn ON bank_credit (bank_txn_id);
CREATE INDEX idx_bank_credit_status ON bank_credit (status, received_at);
