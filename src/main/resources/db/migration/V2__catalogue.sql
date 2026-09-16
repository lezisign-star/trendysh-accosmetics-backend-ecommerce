CREATE TABLE brand (
    id        UUID PRIMARY KEY,
    slug      VARCHAR(120) NOT NULL UNIQUE,
    name      VARCHAR(150) NOT NULL,
    logo_url  VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE category (
    id            UUID PRIMARY KEY,
    slug          VARCHAR(120) NOT NULL UNIQUE,
    name          VARCHAR(150) NOT NULL,
    parent_id     UUID REFERENCES category(id),
    image_url     VARCHAR(500),
    sort_order    INT NOT NULL DEFAULT 0,
    is_published  BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_category_parent ON category (parent_id);

-- The template. Not sellable, not stocked — stock and price live on product_variant.
CREATE TABLE product (
    id                UUID PRIMARY KEY,
    slug              VARCHAR(180) NOT NULL UNIQUE,
    name              VARCHAR(250) NOT NULL,
    brand_id          UUID REFERENCES brand(id),
    category_id       UUID REFERENCES category(id),
    subcategory_id    UUID REFERENCES category(id),
    short_description VARCHAR(500),
    description       TEXT,
    ingredients       TEXT,
    how_to_use        TEXT,
    is_stock_item     BOOLEAN NOT NULL DEFAULT true,
    is_published      BOOLEAN NOT NULL DEFAULT false,
    published_at      TIMESTAMPTZ,
    deleted           BOOLEAN NOT NULL DEFAULT false,
    seo_title         VARCHAR(200),
    seo_description   VARCHAR(500),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID
);

CREATE INDEX idx_product_category ON product (category_id);
CREATE INDEX idx_product_brand ON product (brand_id);

-- Shade, Size, Scent, Finish — the dimensions a variant can vary by.
CREATE TABLE product_attribute (
    id           UUID PRIMARY KEY,
    code         VARCHAR(60) NOT NULL UNIQUE,
    name         VARCHAR(100) NOT NULL,
    display_type VARCHAR(20) NOT NULL CHECK (display_type IN ('SWATCH', 'TEXT', 'DROPDOWN')),
    sort_order   INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product_attribute_value (
    id             UUID PRIMARY KEY,
    attribute_id   UUID NOT NULL REFERENCES product_attribute(id),
    value          VARCHAR(100) NOT NULL,
    display_value  VARCHAR(150),
    swatch_hex     VARCHAR(7),
    swatch_image_url VARCHAR(500),
    sort_order     INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (attribute_id, value)
);

-- The SKU. The only stockable, sellable thing — stock is never held against `product`.
CREATE TABLE product_variant (
    id                 UUID PRIMARY KEY,
    product_id         UUID NOT NULL REFERENCES product(id),
    sku                VARCHAR(60) NOT NULL UNIQUE,
    variant_name       VARCHAR(250) NOT NULL,
    selling_price      NUMERIC(14,2) NOT NULL CHECK (selling_price >= 0),
    compare_at_price   NUMERIC(14,2),
    standard_cost      NUMERIC(14,2),
    uom                VARCHAR(20) NOT NULL DEFAULT 'PIECE',
    weight_grams       NUMERIC(10,2),
    volume_ml          NUMERIC(10,2),
    vat_rate           NUMERIC(5,4) NOT NULL DEFAULT 0.1600,
    vat_exempt         BOOLEAN NOT NULL DEFAULT false,
    reorder_level      NUMERIC(14,3),
    is_active          BOOLEAN NOT NULL DEFAULT true,
    deleted            BOOLEAN NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_variant_product ON product_variant (product_id);

CREATE TABLE variant_attribute_value (
    variant_id         UUID NOT NULL REFERENCES product_variant(id),
    attribute_value_id UUID NOT NULL REFERENCES product_attribute_value(id),
    PRIMARY KEY (variant_id, attribute_value_id)
);

CREATE TABLE variant_barcode (
    id           UUID PRIMARY KEY,
    variant_id   UUID NOT NULL REFERENCES product_variant(id),
    barcode      VARCHAR(64) NOT NULL UNIQUE,
    barcode_type VARCHAR(20) NOT NULL CHECK (barcode_type IN ('EAN13', 'UPC', 'CODE128', 'INTERNAL')),
    is_primary   BOOLEAN NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_variant_barcode_variant ON variant_barcode (variant_id);

CREATE TABLE product_image (
    id            UUID PRIMARY KEY,
    product_id    UUID NOT NULL REFERENCES product(id),
    variant_id    UUID REFERENCES product_variant(id), -- null = applies to whole product
    url           VARCHAR(500) NOT NULL,
    thumbnail_url VARCHAR(500),
    alt_text      VARCHAR(250),
    sort_order    INT NOT NULL DEFAULT 0,
    is_primary    BOOLEAN NOT NULL DEFAULT false,
    source        VARCHAR(20) NOT NULL DEFAULT 'UPLOAD' CHECK (source IN ('UPLOAD', 'CAMERA_CAPTURE')),
    width         INT,
    height        INT,
    byte_size     INT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_image_product ON product_image (product_id);
CREATE INDEX idx_product_image_variant ON product_image (variant_id);
