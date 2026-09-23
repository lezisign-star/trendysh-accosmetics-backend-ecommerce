-- Password resets switch from an emailed link token to an emailed 6-digit OTP.
-- A 6-digit code only has 1,000,000 possible values, so the old table-wide
-- UNIQUE(token_hash) constraint becomes a real collision risk once more than a
-- handful of resets are in flight at once — drop it; lookups are now scoped to
-- the account/staff row instead of a global hash lookup. `attempts` backs a
-- per-code lockout so the narrow code space can't be brute-forced within its
-- (now much shorter) lifetime.
ALTER TABLE customer_email_token DROP CONSTRAINT customer_email_token_token_hash_key;
ALTER TABLE customer_email_token ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE staff_password_reset_token DROP CONSTRAINT staff_password_reset_token_token_hash_key;
ALTER TABLE staff_password_reset_token ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
