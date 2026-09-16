-- gen_random_uuid() is core in PG16+; pgcrypto keeps seed inserts portable
-- against older Postgres (e.g. a managed provider on an older major version).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE branch (
    id                          UUID PRIMARY KEY,
    code                        VARCHAR(20) NOT NULL UNIQUE,
    name                        VARCHAR(120) NOT NULL,
    address_line1               VARCHAR(200),
    address_line2               VARCHAR(200),
    city                        VARCHAR(100),
    phone                       VARCHAR(30),
    email                       VARCHAR(150),
    latitude                    NUMERIC(9,6),
    longitude                   NUMERIC(9,6),
    opening_hours               JSONB,
    is_active                   BOOLEAN NOT NULL DEFAULT true,
    is_default_online_fulfilment BOOLEAN NOT NULL DEFAULT false,
    gl_account_id               UUID,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only one branch may be the default online-fulfilment branch.
CREATE UNIQUE INDEX uq_branch_default_online_fulfilment
    ON branch (is_default_online_fulfilment)
    WHERE is_default_online_fulfilment = true;

CREATE TABLE stock_location (
    id            UUID PRIMARY KEY,
    branch_id     UUID REFERENCES branch(id),
    code          VARCHAR(30) NOT NULL UNIQUE,
    name          VARCHAR(120) NOT NULL,
    location_type VARCHAR(20) NOT NULL
        CHECK (location_type IN (
            'SALES_FLOOR', 'BACKSTORE', 'DAMAGED', 'IN_TRANSIT',
            'SUPPLIER', 'CUSTOMER', 'ADJUSTMENT'
        )),
    -- Stock only genuinely "exists" at an internal location. Virtual locations
    -- (SUPPLIER/CUSTOMER/ADJUSTMENT/IN_TRANSIT) are the other side of a ledger
    -- entry, never a place with a physical shelf count.
    is_internal   BOOLEAN NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_branch_required_for_physical_locations CHECK (
        (location_type IN ('SALES_FLOOR', 'BACKSTORE', 'DAMAGED') AND branch_id IS NOT NULL)
        OR (location_type IN ('IN_TRANSIT', 'SUPPLIER', 'CUSTOMER', 'ADJUSTMENT') AND branch_id IS NULL)
    )
);

CREATE INDEX idx_stock_location_branch ON stock_location (branch_id);
