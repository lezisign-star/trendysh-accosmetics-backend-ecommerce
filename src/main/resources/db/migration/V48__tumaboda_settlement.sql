-- TumaBoda settlement ledger (TUMABODA_PARITY_PLAN.md Section B): Trendysh accumulates what it owes
-- TumaBoda per delivery (tumaboda_cost, already on customer_order) and settles the running balance
-- periodically by bank transfer, rather than per-order in real time. A settlement sweeps every
-- currently-unsettled costed delivery at once -- see TumaBodaSettlementService.
CREATE TABLE tumaboda_settlement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settled_at DATE NOT NULL,
    amount NUMERIC(14, 2) NOT NULL,
    reference VARCHAR(100),
    notes TEXT,
    created_by_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE customer_order
    ADD COLUMN tumaboda_settlement_id UUID REFERENCES tumaboda_settlement(id);
