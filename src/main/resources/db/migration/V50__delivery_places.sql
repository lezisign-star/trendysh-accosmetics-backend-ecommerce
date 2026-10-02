-- Admin-managed delivery places and what each way of getting there costs. "Boda" here is the
-- physically booked boda boda (quoted by staff); TumaBoda's own live quote is separate.
CREATE TABLE delivery_place (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    region VARCHAR(100),
    notes TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_delivery_place_name ON delivery_place (LOWER(name));

CREATE TABLE delivery_place_price (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    place_id UUID NOT NULL REFERENCES delivery_place(id) ON DELETE CASCADE,
    method VARCHAR(10) NOT NULL,
    price_min NUMERIC(14, 2) NOT NULL,
    price_max NUMERIC(14, 2) NOT NULL,
    eta_text VARCHAR(100),
    notes VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_place_method UNIQUE (place_id, method),
    CONSTRAINT ck_price_range CHECK (price_min >= 0 AND price_max >= price_min)
);

CREATE TABLE delivery_place_suggestion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    details TEXT,
    contact VARCHAR(100),
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    resolved_note VARCHAR(300),
    place_id UUID REFERENCES delivery_place(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_place_suggestion_status ON delivery_place_suggestion (status, created_at);
