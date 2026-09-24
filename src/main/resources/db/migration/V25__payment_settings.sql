-- The bank/paybill/WhatsApp details a customer sees on the pay page, moved from static env-var
-- config to something the admin Settings tab can actually edit. Deliberately no seed data here —
-- PaymentSettingsSeeder populates the single row from the CURRENT app.bank.* env values on first
-- boot only, so a live deploy's real values carry over automatically instead of a literal (and
-- sensitive) bank account number ever being committed to this file.
CREATE TABLE payment_settings (
    id               UUID PRIMARY KEY,
    bank_name        VARCHAR(200),
    paybill_number   VARCHAR(30),
    account_name     VARCHAR(200),
    account_number   VARCHAR(50),
    account_branch   VARCHAR(100),
    support_whatsapp VARCHAR(30),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
