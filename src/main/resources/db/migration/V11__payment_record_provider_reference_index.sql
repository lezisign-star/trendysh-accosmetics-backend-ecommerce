-- §4 C4: provider_reference (Daraja's CheckoutRequestID) is the natural
-- correlation key for the M-Pesa callback and is now looked up directly
-- (PaymentRecordRepository.findByProviderReference) instead of a findAll()
-- table scan. A plain UNIQUE index treats NULLs as distinct in Postgres, so
-- CASH/BANK payment records (which never get a provider_reference) are
-- unaffected.
CREATE UNIQUE INDEX idx_payment_record_provider_reference
    ON payment_record (provider_reference);
