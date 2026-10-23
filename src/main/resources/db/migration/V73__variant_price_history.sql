-- The shop's own prices, and when they changed (owner, 2026-10-23: "changes to prices and their dates", and for the normal
-- selling price "keep history automatically, no reason asked"). Every change of a variant's selling_price is written here by a
-- trigger, so it is kept whichever screen or job made it, now or later: the product editor, a quick edit, a bulk price change,
-- a range, a kit's package price, a generated variant.
--  * product_variant.updated_by: the staff member whose save touched the row (set by the application's auditing, "system" for
--    a background job). The trigger copies it only when this same update changed updated_at, i.e. the application wrote the
--    audit fields with the price; a price changed any other way (a script) is recorded with no name rather than with the
--    name of whoever last edited something else on that variant.
--  * variant_price_history: old and new price (null when a variant had none), who, and when. Appended to, never changed.
ALTER TABLE product_variant ADD COLUMN updated_by VARCHAR(200);

CREATE TABLE variant_price_history (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    variant_id UUID NOT NULL REFERENCES product_variant (id),
    old_price  NUMERIC(14, 2),
    new_price  NUMERIC(14, 2),
    changed_by VARCHAR(200),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX variant_price_history_variant_idx ON variant_price_history (variant_id, changed_at DESC);
CREATE INDEX variant_price_history_changed_idx ON variant_price_history (changed_at DESC);

CREATE FUNCTION record_variant_price_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.selling_price IS DISTINCT FROM OLD.selling_price THEN
        INSERT INTO variant_price_history (variant_id, old_price, new_price, changed_by)
        VALUES (NEW.id, OLD.selling_price, NEW.selling_price,
                CASE WHEN NEW.updated_at IS DISTINCT FROM OLD.updated_at THEN NEW.updated_by END);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_variant_price_history
    AFTER UPDATE OF selling_price ON product_variant
    FOR EACH ROW EXECUTE FUNCTION record_variant_price_change();
