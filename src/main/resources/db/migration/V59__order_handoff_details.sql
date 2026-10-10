-- Where a phone-arranged parcel was actually handed over, and whether the shopper was told (owner, 2026-10-10: the shop
-- may choose the sacco for the shopper and inform them). The stage and the receipt/waybill number are known only at
-- the hand-over, after the transporter is agreed, so they live apart from the transporter fields.
ALTER TABLE customer_order ADD COLUMN handoff_stage VARCHAR(200);
ALTER TABLE customer_order ADD COLUMN handoff_reference VARCHAR(60);
ALTER TABLE customer_order ADD COLUMN handoff_note VARCHAR(300);
ALTER TABLE customer_order ADD COLUMN customer_informed_at TIMESTAMPTZ;
