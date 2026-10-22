-- How the shop takes orders (owner, 2026-10-21): trendyshaccosmetic.com becomes the full shop but, until the owner launches
-- online ordering, a shopper sends their basket to the shop on WhatsApp instead of checking out. One switch, one row:
--  * FULL      the website takes orders and payments as usual.
--  * WHATSAPP  the website shows the shop and its prices; the basket is sent on WhatsApp and nothing is saved as an order.
-- Only the storefront on the .com domain honours it (the same app is built for the .site test copy without that flag, which
-- always takes orders), so the API itself keeps working either way. Only the owner and the developer may change it.
-- A new shop starts in WHATSAPP: the switch is flipped to FULL on purpose, at launch.
CREATE TABLE shop_settings (
    id            UUID PRIMARY KEY,
    ordering_mode VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP' CHECK (ordering_mode IN ('FULL', 'WHATSAPP')),
    changed_by    VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO shop_settings (id) VALUES ('00000000-0000-0000-0000-0000000000b1');
