-- Phase 3f cleanup (2026-09-25): the attribute/option model and the old single category/subcategory
-- pair are both fully retired from the Java code as of the same commit this migration ships with —
-- this drops the database objects that backed them. Safe: Phase 3a-3e have been live and
-- re-verified, V27's own pre-flight already found effectively no attribute data to lose on either
-- dev or production, and product.category_id/subcategory_id have been unmapped (read/written by
-- nothing) since V27.
--
-- IF EXISTS throughout: production already had this DDL run directly against it (via the Railway
-- console) before this file could be committed, so this migration must be a no-op there while still
-- doing the real drop on dev/test, where the tables/columns are still present.

DROP TABLE IF EXISTS variant_attribute_value;
DROP TABLE IF EXISTS product_attribute_value;
DROP TABLE IF EXISTS product_attribute;

ALTER TABLE product DROP COLUMN IF EXISTS category_id;
ALTER TABLE product DROP COLUMN IF EXISTS subcategory_id;
