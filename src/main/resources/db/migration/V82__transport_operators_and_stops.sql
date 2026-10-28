-- Metros (owner, 2026-10-28): minibuses run by saccos and companies (Super Metro, Nicco and the rest). A shopper who
-- wants their order by Metro picks the operator, types where it should be dropped, and sees the likely fare (paid to
-- the conductor on delivery, for now). What they type is kept per operator, tidied, so the next shopper can pick it.
--  * transport_operator: the operators staff can switch on and off, edit and add to. Seeded from public lists of Nairobi
--    and Kiambu matatu saccos (Oct 2026); those lists disagree on routes, so staff check and correct them.
--  * transport_stop: a drop-off location for one operator. name_key is the name reduced for matching ("Kencom Stage" and
--    "kencom" are the same place), so a similar location typed later finds the same stop and its fare.
--  * customer_order: which operator and the likely fare the shopper was shown.
--  * customer_account.saved_delivery: the delivery a signed-in shopper last used, so the next order is filled in.
CREATE TABLE IF NOT EXISTS transport_operator (
    id          UUID PRIMARY KEY,
    name        VARCHAR(80) NOT NULL,
    areas       VARCHAR(300),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INTEGER NOT NULL DEFAULT 100,
    picks       INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_transport_operator_name ON transport_operator (lower(name));

CREATE TABLE IF NOT EXISTS transport_stop (
    id              UUID PRIMARY KEY,
    operator_id     UUID NOT NULL REFERENCES transport_operator (id),
    name            VARCHAR(120) NOT NULL,
    name_key        VARCHAR(120) NOT NULL,
    fare_min        NUMERIC(10, 2),
    fare_max        NUMERIC(10, 2),
    fare_confirmed  BOOLEAN NOT NULL DEFAULT FALSE,
    uses            INTEGER NOT NULL DEFAULT 0,
    hidden          BOOLEAN NOT NULL DEFAULT FALSE,
    added_by        VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER' CHECK (added_by IN ('CUSTOMER', 'STAFF')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_transport_stop_key ON transport_stop (operator_id, name_key);

ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS transport_operator_id UUID;
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS delivery_fare_min NUMERIC(10, 2);
ALTER TABLE customer_order ADD COLUMN IF NOT EXISTS delivery_fare_max NUMERIC(10, 2);
ALTER TABLE customer_account ADD COLUMN IF NOT EXISTS saved_delivery TEXT;

-- The operators, the most used first (the order then follows what shoppers pick).
INSERT INTO transport_operator (id, name, areas, sort_order) VALUES
    (gen_random_uuid(), 'Super Metro', 'Thika Road (Juja, Thika), Kikuyu, Kitengela, Ngong, Rongai', 10),
    (gen_random_uuid(), 'Nicco Movers', 'Thika Road, Ruiru, Juja', 20),
    (gen_random_uuid(), 'Embassava', 'Embakasi, Donholm, Savannah, Pipeline', 30),
    (gen_random_uuid(), 'Forward Travellers', 'Umoja, Kayole, Dandora', 40),
    (gen_random_uuid(), 'Zuri', 'Zimmerman, Roysambu, Rongai, Lang''ata', 50),
    (gen_random_uuid(), 'City Hoppa', 'Kawangware, Karen, KNH, Upper Hill, Utawala, Umoja', 60),
    (gen_random_uuid(), 'Kenya Bus Service (KBS)', 'Utawala, Lang''ata, KNH, Kawangware, Riruta', 70),
    (gen_random_uuid(), 'Ongata Line', 'Ongata Rongai, Karen', 80),
    (gen_random_uuid(), 'Kikuyu Travellers', 'Kikuyu', 90),
    (gen_random_uuid(), 'MSL (Mwiki)', 'Kasarani, Mwiki', 100),
    (gen_random_uuid(), 'Thika Road Transporters', 'Ruiru, Juja, Githurai, Thika', 110),
    (gen_random_uuid(), 'Githurai 45', 'Githurai', 120),
    (gen_random_uuid(), 'Umoja Inner Core', 'Umoja', 130),
    (gen_random_uuid(), 'Latema', 'Nairobi', 140),
    (gen_random_uuid(), 'Lopha', 'Pangani, Ndenderu, Wangige, Ruiru, Juja', 150),
    (gen_random_uuid(), 'Kangemi Matatu Owners', 'Westlands, Kangemi', 160),
    (gen_random_uuid(), 'Ngong Travellers', 'Ngong', 170),
    (gen_random_uuid(), 'Kaka Travellers', 'Kiambu town', 180),
    (gen_random_uuid(), 'Kiambu Marafiki', 'Ridgeways, Kiambu town', 190),
    (gen_random_uuid(), 'Nawasuku', 'Kahawa Sukari, Ruiru', 200),
    (gen_random_uuid(), 'Tawala (Utawala)', 'Utawala', 210),
    (gen_random_uuid(), 'Kadana Travellers', 'Kawangware, Dagoretti, Kikuyu', 220)
ON CONFLICT DO NOTHING;
