-- What a customer sees and types when paying by M-PESA Paybill is now managed from the admin
-- Settings screen rather than only from environment variables:
--   paybill_merchant_name    the business name the customer's phone shows before they enter their PIN
--   paybill_account_template what to type as the Account Number, with placeholders resolved per order:
--                            <name> <phone> <order> <ref>   (e.g. "NJERI CHRISTINE", or "01100123#<name>")
-- A null template falls back to app.bank.paybill-account-template, so existing deployments are unchanged
-- until someone sets it here.
ALTER TABLE payment_settings
    ADD COLUMN paybill_merchant_name VARCHAR(200),
    ADD COLUMN paybill_account_template VARCHAR(100);
