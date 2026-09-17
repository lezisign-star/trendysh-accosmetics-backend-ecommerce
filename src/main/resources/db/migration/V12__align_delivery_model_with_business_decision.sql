-- Founder decision (frontend rebrand session, 2026-09-15): TumaBoda is no
-- longer used at all, and the shop never charges or estimates a delivery fee
-- — customers pick a transport preference (rider/vehicle/pickup), the team
-- confirms the actual courier and price by phone/WhatsApp afterwards, and
-- that amount is paid directly to the courier, separate from what the shop
-- charges. The backend's checkout total must reflect only the product
-- subtotal, never a delivery estimate — see CheckoutService's own javadoc
-- for the VAT-inclusive-price framing this sits alongside.
--
-- V9's CHECK constraint allowed DELIVERY_TUMABODA and delivery_fee was only
-- ever set to 0 by convention (CheckoutService always did so, but nothing
-- stopped a future write path from setting a real figure). Both are now
-- enforced structurally rather than left as an assumption a future
-- implementer could accidentally violate.

-- No existing rows use DELIVERY_TUMABODA or a non-zero delivery_fee (checked
-- against nairobi_beauty_hub_dev before writing this migration), so neither
-- constraint needs a data backfill.

ALTER TABLE customer_order
    DROP CONSTRAINT customer_order_fulfilment_type_check;

ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_fulfilment_type_check
        CHECK (fulfilment_type IN ('DELIVERY_COURIER', 'BRANCH_PICKUP'));

ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_delivery_fee_always_zero CHECK (delivery_fee = 0);
