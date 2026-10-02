-- Display-only plumbing (TUMABODA_PARITY_PLAN.md Section C): the code staff give the rider at
-- pickup, once TumaBoda's real API confirms the field exists and returns it. Blank/null today.
ALTER TABLE customer_order
    ADD COLUMN tumaboda_pickup_otp VARCHAR(20);
