-- Professional Kits (2026-09-28): an admin-curated bundle of existing product variants + fixed
-- quantities, sold at one fixed, snapshotted package price. A kit IS a real Product + ProductVariant
-- (not a parallel catalog entity) so it flows through the existing search/category/cart/checkout
-- paths for free — see CatalogService.searchSellable and CheckoutService. These two tables hold only
-- the admin-curated bundle definition.

ALTER TABLE product_variant ADD COLUMN is_kit BOOLEAN NOT NULL DEFAULT false;
-- A kit variant holds no physical stock of its own (the real stock lives on its components' own
-- balances). This lets the two read paths that would otherwise gate on stock — CatalogService's
-- "in stock" badge and CartService's add-to-cart courtesy check — treat it as always available.
-- Real availability is authoritatively enforced at checkout, which reserves each component instead
-- of this stockless SKU.

CREATE TABLE kit (
    id                UUID PRIMARY KEY,
    variant_id        UUID NOT NULL UNIQUE REFERENCES product_variant(id),
    profession_label  VARCHAR(100) NOT NULL,
    profession_slug   VARCHAR(100) NOT NULL UNIQUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kit_component (
    id                    UUID PRIMARY KEY,
    kit_id                UUID NOT NULL REFERENCES kit(id) ON DELETE CASCADE,
    line_no               INTEGER NOT NULL,
    component_variant_id  UUID NOT NULL REFERENCES product_variant(id),
    qty                   NUMERIC(14,3) NOT NULL CHECK (qty > 0),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (kit_id, component_variant_id)
);

CREATE INDEX idx_kit_component_kit ON kit_component (kit_id);
CREATE INDEX idx_kit_component_variant ON kit_component (component_variant_id);

-- Fulfilment visibility: a kit's own order line needs child lines naming its REAL components (each
-- with its own real reservation) without breaking order-total arithmetic, which must keep summing
-- exactly the parentless (top-level) lines it always has. reservation_id on customer_order_item is
-- already nullable, so a kit's parentless, reservation-less line needs no further schema change.
ALTER TABLE customer_order_item
    ADD COLUMN parent_item_id UUID REFERENCES customer_order_item(id) ON DELETE CASCADE;
CREATE INDEX idx_customer_order_item_parent ON customer_order_item (parent_item_id);
