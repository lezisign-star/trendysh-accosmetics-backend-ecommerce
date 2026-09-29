-- Seeded fallback for TumaBoda address search (2026-09-29), modelled on Moments Packaging's
-- CommonDeliveryArea: a curated list of well-known Nairobi-metro estates/areas, used so the
-- checkout address picker always has something sensible to suggest even when TumaBoda's own
-- live autocomplete returns nothing (unreachable, rate-limited, or too vague a query) or the
-- integration is off entirely. Nairobi-only, matching delivery.tsx's own stated TumaBoda
-- footprint ("same-day in Nairobi... inside Nairobi and the metro") — unlike Moments, which also
-- covers Thika/Ruiru/Juja for its own delivery area.
--
-- Rows are inserted by CommonDeliveryAreaSeeder at boot (ApplicationRunner, skips if the table
-- already has rows), not here: the list is expected to grow/change without a migration each time.

CREATE TABLE common_delivery_area (
    id          UUID PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    county      VARCHAR(60),
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_common_delivery_area_name ON common_delivery_area (name);
