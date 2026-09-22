-- Merchandising: New Arrival / Popular are manual per-product flags; a flash deal is a per-variant
-- sale price with its own deadline; a promo campaign is one admin-controlled site-wide percent-off
-- at a time. See NairobiBeautyHub_Merchandising_Promo_Plan.md for the full design and rules.

ALTER TABLE product ADD COLUMN is_popular BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE product ADD COLUMN is_new_arrival BOOLEAN NOT NULL DEFAULT false;

-- A product's own flash deal always wins over an active campaign (most-specific-wins — see
-- EffectivePriceResolver). Both null = no deal; the service layer enforces they're set together.
ALTER TABLE product_variant ADD COLUMN deal_price NUMERIC(14,2);
ALTER TABLE product_variant ADD COLUMN deal_ends_at TIMESTAMPTZ;

-- History is kept (never deleted) so past campaigns aren't lost; a lapsed or superseded one is
-- just left with ends_at in the past or is_active = false. "Currently active" = is_active AND
-- now() BETWEEN starts_at AND ends_at — the service layer enforces at most one such row exists.
CREATE TABLE promo_campaign (
    id          UUID PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    percent_off NUMERIC(5,2) NOT NULL,
    starts_at   TIMESTAMPTZ NOT NULL,
    ends_at     TIMESTAMPTZ NOT NULL,
    is_active   BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_promo_campaign_window ON promo_campaign (starts_at, ends_at) WHERE is_active = true;
