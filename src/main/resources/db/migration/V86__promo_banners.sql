-- Banner builder (owner, 2026-10-31): staff make each banner on purpose. They choose where it shows, what it links to
-- and which products it features, then get its picture (made by Gemini from the products' real photos, uploaded, or a
-- product photo), see it as customers will, give it dates and approve it. Nothing is made unless staff ask, because each
-- picture costs. The occasion banners of V84 stay for what is already picked; occasions now only lend their colours.

CREATE TABLE promo_banner (
    id UUID PRIMARY KEY,
    -- staff's own name for it ("Black November hero"), never shown to customers
    name VARCHAR(120) NOT NULL,
    -- HERO (home carousel), STRIP (wide band), TILE (category promo tile), OFFER_CARD, GRID_TILE, CIRCLE (the V84 slots)
    placement VARCHAR(12) NOT NULL,
    -- CIRCLE: whose circle it is; STRIP and TILE: shown on that category's pages only (none = everywhere)
    category_id UUID REFERENCES category(id),
    -- where a tap goes: DEALS, CATEGORY, PRODUCT
    link_type VARCHAR(10) NOT NULL DEFAULT 'DEALS',
    link_category_id UUID REFERENCES category(id),
    link_variant_id UUID REFERENCES product_variant(id),
    headline VARCHAR(80),
    subline VARCHAR(140),
    cta_label VARCHAR(30),
    -- AUTO: worked out from the featured products' live deals; CUSTOM: offer_text; NONE
    offer_mode VARCHAR(6) NOT NULL DEFAULT 'AUTO',
    offer_text VARCHAR(40),
    show_countdown BOOLEAN NOT NULL DEFAULT FALSE,
    -- an occasion lends its colours; none = the shop's own
    occasion_id UUID REFERENCES occasion(id),
    -- light words over a dark picture, or dark over a light one
    text_tone VARCHAR(5) NOT NULL DEFAULT 'light',
    -- the chosen picture, and the point of it to keep in view when a phone crops it (percent across, down)
    image_url TEXT,
    focus_x SMALLINT NOT NULL DEFAULT 50,
    focus_y SMALLINT NOT NULL DEFAULT 50,
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    -- order in the hero carousel and among banners of the same place, lowest first
    sort_order INT NOT NULL DEFAULT 0,
    -- DRAFT, APPROVED (scheduled, live or ended by its dates), PAUSED
    status VARCHAR(10) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(200),
    approved_by VARCHAR(200),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_promo_banner_live ON promo_banner (placement, starts_at, ends_at) WHERE status = 'APPROVED';

-- The products a banner features (up to four), their thumbnails and prices drawn on it.
CREATE TABLE promo_banner_product (
    banner_id UUID NOT NULL REFERENCES promo_banner(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variant(id) ON DELETE CASCADE,
    position INT NOT NULL,
    PRIMARY KEY (banner_id, variant_id)
);

-- The pictures a banner could have: made by Gemini (MAKING until done), uploaded, or a product photo.
CREATE TABLE promo_banner_picture (
    id UUID PRIMARY KEY,
    banner_id UUID NOT NULL REFERENCES promo_banner(id) ON DELETE CASCADE,
    source VARCHAR(8) NOT NULL,
    status VARCHAR(8) NOT NULL,
    image_url TEXT,
    -- what staff asked for, and why it failed
    brief TEXT,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_promo_banner_picture_banner ON promo_banner_picture (banner_id, created_at);
